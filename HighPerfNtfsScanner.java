package ntfs; // 声明包名，NTFS扫描器所属包

// ===== JNA 相关导入：用于调用 Windows 原生 API =====
import com.sun.jna.Native; // JNA 原生方法调用工具
import com.sun.jna.platform.win32.Kernel32; // Windows Kernel32 API（FindFirstFile/FindNextFile 等）
import com.sun.jna.platform.win32.WinBase; // Windows 基础类型（INVALID_HANDLE_VALUE 等）
import com.sun.jna.platform.win32.WinBase.WIN32_FIND_DATA; // FindFirstFile 返回的文件信息结构体
import com.sun.jna.platform.win32.WinNT; // Windows NT 类型（HANDLE、FILETIME 等）

// ===== Java 标准库导入 =====
import java.io.BufferedWriter; // 带缓冲的字符写入流
import java.io.FileOutputStream; // 文件字节输出流
import java.io.IOException; // IO 异常
import java.io.OutputStreamWriter; // 字节流→字符流桥梁（指定编码）
import java.nio.charset.StandardCharsets; // 标准字符集（UTF-8）
import java.time.Instant; // 时间戳
import java.time.ZoneId; // 时区
import java.time.format.DateTimeFormatter; // 日期时间格式化器
import java.util.ArrayList; // 动态数组
import java.util.List; // List 接口
import java.util.concurrent.BlockingQueue; // 阻塞队列接口
import java.util.concurrent.LinkedBlockingQueue; // 链表实现的有界阻塞队列
import java.util.concurrent.ScheduledExecutorService; // 定时任务执行器
import java.util.concurrent.Executors; // 执行器工厂
import java.util.concurrent.Semaphore; // 信号量（限制并发数）
import java.util.concurrent.TimeUnit; // 时间单位枚举
import java.util.concurrent.atomic.AtomicLong; // 原子长整型（线程安全计数器）
import java.util.concurrent.atomic.LongAdder; // 高并发累加器（比 AtomicLong 更高效）

/**
 * 高性能NTFS目录扫描器（基于 Windows FindFirstFile/FindNextFile API + 虚拟线程）。
 *
 * <p>无需管理员权限，适用于超大磁盘（200TB级）流式扫描。核心特性：
 * <ul>
 *   <li>生产者-消费者模式 + 有界阻塞队列，实现背压控制，避免OOM</li>
 *   <li>虚拟线程递归遍历目录，信号量限制并发数</li>
 *   <li>FindFirstFile/FindNextFile 一次调用即获取全部属性（大小/时间/类型），无需额外 GetFileAttributesEx</li>
 *   <li>FileInfo 使用 record 值对象，时间戳存原始 FILETIME long，输出时再格式化</li>
 *   <li>批量CSV写入（每批20000条），1MB缓冲，减少系统调用次数</li>
 *   <li>多消费者线程并行格式化，synchronized 串行写入，提升CPU利用率</li>
 *   <li>LongAdder 替代 AtomicLong 用于统计计数器，消除高并发CAS竞争</li>
 *   <li>\\?\ 前缀支持260+长路径</li>
 * </ul>
 *
 * <p><b>性能优化说明</b>：
 * <ul>
 *   <li><b>队列阻塞</b>：使用 offer(timeout)+重试 替代 put()，配合100K队列容量和1MB IO缓冲</li>
 *   <li><b>计数器竞争</b>：LongAdder 使用 cell 数组分散CAS，500线程下比 AtomicLong 快5~10倍；
 *       idCounter/pendingTasks 仍用 AtomicLong（需精确返回值）</li>
 *   <li><b>进度报告</b>：定时器线程每5秒打印一次，彻底移除热路径中的取模检查</li>
 *   <li><b>多消费者</b>：N个线程并行格式化StringBuilder，synchronized(writer) 串行写入磁盘</li>
 *   <li><b>虚拟线程</b>：每个目录一个虚拟线程，信号量(500)限制并发；虚拟线程仅几KB栈，
 *       阻塞IO时不占载体线程，创建/销毁开销极低，不影响性能</li>
 * </ul>
 */
public class HighPerfNtfsScanner {

    // ===== 配置参数 =====

    /** 生产者-消费者队列容量（背压控制），10万容量吸收突发流量 */
    private static final int QUEUE_CAPACITY = 100_000;

    /** CSV批量写入大小，单次刷盘的条目数 */
    private static final int BATCH_SIZE = 20_000;

    /** 默认最大并发目录遍历数 */
    private static final int DEFAULT_MAX_CONCURRENT = 500;

    /** 进度报告间隔（秒），由定时器线程负责 */
    private static final int PROGRESS_INTERVAL_SECONDS = 5;

    /** CSV缓冲区大小（1MB），减少系统调用次数 */
    private static final int IO_BUFFER_SIZE = 1024 * 1024;

    /** 默认消费者线程数（取CPU核心数与4的较小值） */
    private static final int DEFAULT_CONSUMER_THREADS =
            Math.min(4, Runtime.getRuntime().availableProcessors());

    // ===== Windows 常量 =====

    /** Windows 文件属性标志位：目录 */
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;

    /** Windows 文件属性标志位：重解析点（符号链接/挂载点） */
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;

    /** Windows 错误码：文件不存在 */
    private static final int ERROR_FILE_NOT_FOUND = 2;

    /** Windows 错误码：路径不存在（父目录找不到） */
    private static final int ERROR_PATH_NOT_FOUND = 3;

    /** Windows 错误码：权限拒绝 */
    private static final int ERROR_ACCESS_DENIED = 5;

    /** 日期时间格式化器（本地时区，格式 yyyy-MM-dd HH:mm:ss） */
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // ===== 共享状态 =====

    /** 生产者-消费者共享阻塞队列 */
    private final BlockingQueue<FileInfo> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    // 统计计数器：使用 LongAdder 替代 AtomicLong
    // LongAdder 在高并发下通过 cell 数组分散 CAS 竞争，sum() 时合并；
    // 这些计数器只在进度报告/汇总时读取，写多读少，完美匹配 LongAdder 场景
    /** 已扫描文件总数（含目录） */
    private final LongAdder fileCount = new LongAdder();

    /** 已扫描目录数 */
    private final LongAdder dirCount = new LongAdder();

    /** 累计文件大小（字节） */
    private final LongAdder totalSize = new LongAdder();

    /** 扫描错误数 */
    private final LongAdder errorCount = new LongAdder();

    // 以下两个计数器仍用 AtomicLong，因为需要精确的返回值
    /** 记录ID自增计数器（类似MFT记录号），根目录为0；getAndIncrement 需返回唯一值 */
    private final AtomicLong idCounter = new AtomicLong(0);

    /** 活跃生产者任务计数，decrementAndGet==0 用于终止判断 */
    private final AtomicLong pendingTasks = new AtomicLong(0);

    /** 信号量限制并发目录遍历数 */
    private final Semaphore semaphore;

    /** 消费者线程数 */
    private final int consumerThreadCount;

    /** 标志位：所有生产者是否已完成（volatile 保证可见性） */
    private volatile boolean producersDone = false;

    /** 扫描开始时间戳（毫秒） */
    private long scanStart;

    /** 扫描根路径（以反斜杠结尾），用于 parentId=0 条目拼接前缀 */
    private String rootPath;

    /**
     * FileInfo 值对象（Record），减少对象头开销。
     * 使用 id + parentId 替代完整路径，存储数据库可大幅节省空间。
     *
     * @param id         本条目唯一记录ID（类似MFT记录号）
     * @param parentId   父目录记录ID，根目录自身为-1
     * @param name       文件名（parentId=0时含完整搜索前缀）
     * @param ext        扩展名（小写，目录为空串）
     * @param isDir      是否目录（1=目录，0=文件）
     * @param size       文件大小（字节）
     * @param createTime 创建时间（FILETIME原始long）
     * @param modifyTime 修改时间（FILETIME原始long）
     */
    public record FileInfo(
            long id,
            long parentId,
            String name,
            String ext,
            byte isDir,
            long size,
            long createTime,
            long modifyTime
    ) {
    }

    /**
     * 构造扫描器，指定生产者并发数和消费者线程数。
     *
     * @param concurrency       最大并发目录遍历数（至少1）
     * @param consumerThreadCount CSV消费者线程数（至少1）
     */
    public HighPerfNtfsScanner(int concurrency, int consumerThreadCount) {
        this.semaphore = new Semaphore(Math.max(1, concurrency));
        this.consumerThreadCount = Math.max(1, consumerThreadCount);
    }

    /** 构造扫描器，指定生产者并发数，消费者线程数取默认值 */
    public HighPerfNtfsScanner(int concurrency) {
        this(concurrency, DEFAULT_CONSUMER_THREADS);
    }

    /** 默认构造扫描器 */
    public HighPerfNtfsScanner() {
        this(DEFAULT_MAX_CONCURRENT, DEFAULT_CONSUMER_THREADS);
    }

    /**
     * 扫描入口：启动进度定时器、消费者线程、生产者，等待全部完成。
     *
     * @param root   扫描根路径（如 "E:\"）
     * @param output 输出CSV文件路径
     * @throws Exception 扫描过程中的异常
     */
    public void scan(String root, String output) throws Exception {
        // 规范化根路径
        String normalizedRoot = normalizeRoot(root);
        // 确保 rootPath 以反斜杠结尾
        this.rootPath = normalizedRoot.endsWith("\\") ? normalizedRoot : normalizedRoot + "\\";
        // 记录开始时间
        scanStart = System.currentTimeMillis();

        // 启动进度定时器（守护线程，每5秒打印一次进度）
        ScheduledExecutorService progressTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "progress-timer");
            t.setDaemon(true); // 守护线程，不阻止JVM退出
            return t;
        });
        progressTimer.scheduleAtFixedRate(this::printProgress,
                PROGRESS_INTERVAL_SECONDS, PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS);

        try (FileOutputStream fos = new FileOutputStream(output); // 文件输出流
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8); // UTF-8编码
             BufferedWriter writer = new BufferedWriter(osw, IO_BUFFER_SIZE)) { // 1MB缓冲

            // 写入 UTF-8 BOM
            fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

            // 写入CSV表头
            writer.write("记录ID,父记录ID,文件名,扩展名,是否文件夹,文件大小,创建时间,修改时间");
            writer.newLine();

            // 启动消费者线程（多线程共享同一个 writer，synchronized 串行写入）
            Thread[] consumers = new Thread[consumerThreadCount];
            for (int i = 0; i < consumerThreadCount; i++) {
                consumers[i] = new Thread(new CsvWriter(writer), "csv-writer-" + i);
                consumers[i].start();
            }

            // 启动生产者：根目录遍历（虚拟线程）
            long rootId = idCounter.getAndIncrement(); // 根目录ID=0
            pendingTasks.set(1); // 初始1个活跃任务
            Thread.startVirtualThread(() -> traverseDirectory(normalizedRoot, rootId, true));

            // 等待所有消费者完成
            for (Thread t : consumers) {
                t.join();
            }

            // 最终确保所有缓冲数据写入磁盘
            writer.flush();
        } finally {
            // 关闭进度定时器
            progressTimer.shutdownNow();
        }

        // 打印汇总
        long elapsed = System.currentTimeMillis() - scanStart;
        printSummary(elapsed);
    }

    // ===== 生产者：目录遍历 =====

    /**
     * 遍历单个目录（每个目录一个虚拟线程任务）。
     *
     * @param dirPath 目录绝对路径
     * @param dirId   该目录的记录ID
     * @param isRoot  是否为扫描根目录
     */
    private void traverseDirectory(String dirPath, long dirId, boolean isRoot) {
        try {
            semaphore.acquire(); // 获取信号量许可
            try {
                enumerateDirectory(dirPath, dirId, isRoot); // 枚举目录内容
            } finally {
                semaphore.release(); // 释放许可
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志
        } catch (Throwable t) {
            errorCount.increment(); // 错误计数+1（LongAdder）
            System.err.println("遍历目录失败: " + dirPath + " -> " + t.getMessage());
        } finally {
            // 活跃任务归零时通知消费者结束
            if (pendingTasks.decrementAndGet() == 0) {
                producersDone = true;
            }
        }
    }

    /**
     * 使用 FindFirstFile/FindNextFile 枚举目录内容。
     * 使用 \\?\ 前缀支持超过260字符的长路径。
     *
     * @param dirPath 目录绝对路径
     * @param dirId   该目录的记录ID
     * @param isRoot  是否为扫描根目录
     */
    private void enumerateDirectory(String dirPath, long dirId, boolean isRoot) {
        // 构造查找通配符，并添加 \\?\ 前缀支持长路径
        String pattern = toLongPath(joinPath(dirPath, "*"));
        // 创建 WIN32_FIND_DATA 结构体
        WIN32_FIND_DATA data = new WIN32_FIND_DATA();
        // FindFirstFile 查找第一个文件
        WinNT.HANDLE hFind = Kernel32.INSTANCE.FindFirstFile(pattern, data.getPointer());

        // 检查句柄有效性
        if (hFind == null || hFind.equals(WinBase.INVALID_HANDLE_VALUE)) {
            int err = Kernel32.INSTANCE.GetLastError(); // 获取错误码
            errorCount.increment(); // 错误计数+1
            if (err == ERROR_ACCESS_DENIED) {
                System.err.println("权限拒绝: " + dirPath);
            } else if (err == ERROR_PATH_NOT_FOUND) {
                System.err.println("路径不存在: " + dirPath);
            } else if (err != ERROR_FILE_NOT_FOUND) {
                System.err.println("FindFirstFile 失败: " + dirPath + " (错误码=" + err + ")");
            }
            return; // 无法打开目录
        }

        try {
            data.read(); // 读取结构体数据
            while (true) {
                String name = Native.toString(data.cFileName); // 提取文件名
                if (name.equals(".")) {
                    // 当前目录条目
                    if (isRoot) {
                        // 根目录输出自身记录
                        long size = ((long) data.nFileSizeHigh << 32) | (data.nFileSizeLow & 0xFFFFFFFFL);
                        long createTime = fileTimeToLong(data.ftCreationTime);
                        long modifyTime = fileTimeToLong(data.ftLastWriteTime);
                        enqueue(new FileInfo(dirId, -1L, sanitize(dirPath), "", (byte) 1, size, createTime, modifyTime));
                        fileCount.increment(); // LongAdder
                        dirCount.increment(); // LongAdder
                    }
                } else if (!name.equals("..")) {
                    // 跳过父目录条目，处理其他条目
                    processEntry(dirPath, dirId, name, data);
                }
                // 查找下一个
                if (!Kernel32.INSTANCE.FindNextFile(hFind, data.getPointer())) {
                    break;
                }
                data.read();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Kernel32.INSTANCE.FindClose(hFind); // 关闭句柄
        }
    }

    /**
     * 处理单个条目：分配ID并放入队列，必要时递归子目录。
     *
     * @param parentPath 父目录路径
     * @param parentId   父目录记录ID
     * @param name       条目文件名
     * @param data       Windows 查找结果
     * @throws InterruptedException 入队被中断
     */
    private void processEntry(String parentPath, long parentId, String name, WIN32_FIND_DATA data) throws InterruptedException {
        int attr = data.dwFileAttributes; // 文件属性
        boolean isDir = (attr & FILE_ATTRIBUTE_DIRECTORY) != 0; // 是否目录
        boolean isReparse = (attr & FILE_ATTRIBUTE_REPARSE_POINT) != 0; // 是否重解析点

        String fullName = joinPath(parentPath, name); // 完整路径（用于递归）
        long size = ((long) data.nFileSizeHigh << 32) | (data.nFileSizeLow & 0xFFFFFFFFL);
        long createTime = fileTimeToLong(data.ftCreationTime);
        long modifyTime = fileTimeToLong(data.ftLastWriteTime);

        String ext = isDir ? "" : extractExtension(name); // 扩展名
        String safeName = sanitize(name); // 过滤控制字符
        // parentId=0 时拼接搜索前缀
        if (parentId == 0) {
            safeName = rootPath + safeName;
        }

        long id = idCounter.getAndIncrement(); // 分配唯一ID
        enqueue(new FileInfo(id, parentId, safeName, ext, (byte) (isDir ? 1 : 0), size, createTime, modifyTime));

        // 统计计数（LongAdder，无CAS竞争）
        fileCount.increment();
        if (isDir) {
            dirCount.increment();
        }
        totalSize.add(size);

        // 注意：进度报告已移至定时器线程，此处不再做取模检查

        // 目录且非重解析点：递归遍历（新虚拟线程）
        if (isDir && !isReparse) {
            pendingTasks.incrementAndGet(); // 活跃任务+1
            final String childPath = fullName;
            Thread.startVirtualThread(() -> traverseDirectory(childPath, id, false));
        }
    }

    /**
     * 将 FileInfo 放入共享队列（带超时的 offer + 重试，避免无限阻塞）。
     *
     * @param info 待入队的文件信息
     * @throws InterruptedException 等待期间被中断
     */
    private void enqueue(FileInfo info) throws InterruptedException {
        while (!queue.offer(info, 1, TimeUnit.SECONDS)) {
            System.err.println("背压警告: 队列已满，生产者等待中 (队列容量=" + QUEUE_CAPACITY + ")");
        }
    }

    // ===== 消费者：批量写CSV =====

    /**
     * CSV 消费者任务（内部类）。
     * 多个消费者线程共享同一个 BufferedWriter：
     * - 格式化（StringBuilder 构建）并行执行，无锁
     * - 写入（writer.write）串行执行，synchronized 保证线程安全
     */
    private class CsvWriter implements Runnable {

        /** 共享的缓冲写入器（多线程 synchronized 访问） */
        private final BufferedWriter writer;

        /** 每个消费者独立的批量缓冲区 */
        private final List<FileInfo> batch = new ArrayList<>(BATCH_SIZE);

        /**
         * 构造CSV写入器。
         *
         * @param writer 共享的 BufferedWriter
         */
        CsvWriter(BufferedWriter writer) {
            this.writer = writer;
        }

        /** 消费者主循环 */
        @Override
        public void run() {
            try {
                while (true) {
                    // 从队列取一条，超时50ms
                    FileInfo info = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (info != null) {
                        batch.add(info); // 加入批次
                        queue.drainTo(batch, BATCH_SIZE - batch.size()); // 批量获取
                    }
                    // 批次满，刷盘
                    if (batch.size() >= BATCH_SIZE) {
                        flushBatch();
                    }
                    // 生产者完成且队列空：刷盘剩余数据并退出
                    if (info == null && producersDone && queue.isEmpty()) {
                        flushBatch();
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.err.println("CSV写入失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * 批量格式化为CSV行并写入。
         * 格式化（StringBuilder）无锁并行；写入 synchronized 串行。
         *
         * @throws IOException 写入失败
         */
        private void flushBatch() throws IOException {
            if (batch.isEmpty()) {
                return; // 空批次跳过
            }
            // 格式化（无锁，每个消费者独立构建 StringBuilder）
            StringBuilder sb = new StringBuilder(batch.size() * 128);
            for (FileInfo info : batch) {
                sb.append(info.id())              // 记录ID
                        .append(',')               // 分隔符
                        .append(info.parentId())   // 父记录ID
                        .append(',')
                        .append(quote(info.name()))     // 文件名
                        .append(',')
                        .append(quote(info.ext()))      // 扩展名
                        .append(',')
                        .append(info.isDir())      // 是否文件夹
                        .append(',')
                        .append(info.size())       // 文件大小
                        .append(',')
                        .append(quote(formatFileTime(info.createTime()))) // 创建时间
                        .append(',')
                        .append(quote(formatFileTime(info.modifyTime()))) // 修改时间
                        .append('\n');
            }
            // 写入（synchronized 串行，保证线程安全）
            synchronized (writer) {
                writer.write(sb.toString());
            }
            batch.clear(); // 清空批次
        }
    }

    // ===== 工具方法 =====

    /**
     * 将路径转换为 Windows 长路径格式（\\?\ 前缀），支持超过260字符的路径。
     * 仅用于 Windows API 调用，不影响存储的路径值。
     * <ul>
     *   <li>本地路径 C:\... → \\?\C:\...</li>
     *   <li>UNC路径 \\server\share → \\?\UNC\server\share</li>
     * </ul>
     *
     * @param path 原始路径
     * @return 带 \\?\ 前缀的长路径
     */
    static String toLongPath(String path) {
        // 已有前缀，直接返回
        if (path.startsWith("\\\\?\\")) {
            return path;
        }
        // UNC 路径：\\server\share → \\?\UNC\server\share
        if (path.startsWith("\\\\")) {
            return "\\\\?\\UNC\\" + path.substring(2);
        }
        // 本地路径：C:\... → \\?\C:\...
        return "\\\\?\\" + path;
    }

    /**
     * CSV字段双引号包裹，内部双引号转义为两个。
     *
     * @param s 原始字符串
     * @return 双引号包裹并转义后的字符串
     */
    static String quote(String s) {
        if (s == null || s.isEmpty()) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') {
                sb.append("\"\""); // 转义
            } else {
                sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * FILETIME (低32位+高32位) 转为 long。
     *
     * @param ft Windows FILETIME 结构体
     * @return 64位 long 值
     */
    static long fileTimeToLong(WinNT.FILETIME ft) {
        if (ft == null) {
            return 0L;
        }
        return ((long) ft.dwHighDateTime << 32) | (ft.dwLowDateTime & 0xFFFFFFFFL);
    }

    /**
     * FILETIME 格式化为本地时间字符串（yyyy-MM-dd HH:mm:ss）。
     *
     * @param fileTime FILETIME 原始 long 值
     * @return 格式化后的时间字符串，无效返回空串
     */
    static String formatFileTime(long fileTime) {
        if (fileTime <= 0) {
            return "";
        }
        long epochMillis = fileTime / 10000L - 11644473600000L;
        if (epochMillis < 0) {
            return "";
        }
        return DATE_FMT.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * 提取扩展名（小写），无扩展名或目录返回空串。
     *
     * @param name 文件名
     * @return 小写扩展名
     */
    static String extractExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 路径拼接，避免盘根出现双反斜杠。
     *
     * @param parent 父路径
     * @param child  子路径
     * @return 拼接后的完整路径
     */
    static String joinPath(String parent, String child) {
        if (parent.endsWith("\\")) {
            return parent + child;
        }
        return parent + "\\" + child;
    }

    /**
     * 规范化扫描根路径（去引号、去尾部反斜杠、补盘根反斜杠）。
     *
     * @param root 原始输入路径
     * @return 规范化后的路径
     */
    static String normalizeRoot(String root) {
        root = root.trim();
        if (root.startsWith("\"") && root.endsWith("\"") && root.length() >= 2) {
            root = root.substring(1, root.length() - 1);
        }
        while (root.length() > 3 && root.endsWith("\\")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.length() == 2 && root.charAt(1) == ':') {
            root = root + "\\";
        }
        return root;
    }

    /**
     * 过滤文件名中的控制字符，替换为下划线。
     *
     * @param s 原始字符串
     * @return 过滤后的字符串
     */
    static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (isControlChar(ch)) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
                sb.append('_');
            } else if (sb != null) {
                sb.append(ch);
            }
        }
        return sb != null ? sb.toString() : s;
    }

    /**
     * 判断字符是否为控制字符。
     *
     * @param ch 待检查的字符
     * @return true 表示是控制字符
     */
    static boolean isControlChar(char ch) {
        return (ch >= 0x00 && ch <= 0x1F)       // ASCII控制字符
                || ch == 0x7F                     // DEL
                || (ch >= 0x80 && ch <= 0x9F)     // C1控制字符
                || ch == 0x2028                   // 行分隔符
                || ch == 0x2029                   // 段分隔符
                || ch == 0xFFFD                   // 替换字符
                || ch == 0xFEFF                   // BOM
                || (ch >= 0xFFF9 && ch <= 0xFFFB); // 行注释字符
    }

    /**
     * 打印扫描进度（由定时器线程调用）。
     */
    private void printProgress() {
        long count = fileCount.sum(); // LongAdder 求和
        long size = totalSize.sum();
        long elapsedMs = System.currentTimeMillis() - scanStart;
        double elapsedSec = elapsedMs / 1000.0;
        double throughput = elapsedSec > 0 ? count / elapsedSec : 0;
        System.out.printf("进度: %,d 条 | 累计大小: %,.2f GB | 速度: %,.0f 条/秒 | 已用: %.0fs%n",
                count, size / (1024.0 * 1024.0 * 1024.0), throughput, elapsedSec);
    }

    /**
     * 打印扫描汇总报告。
     *
     * @param elapsedMs 总耗时（毫秒）
     */
    private void printSummary(long elapsedMs) {
        long total = fileCount.sum();   // 总记录数
        long dirs = dirCount.sum();     // 目录数
        long files = total - dirs;      // 文件数
        long size = totalSize.sum();    // 累计大小
        long errors = errorCount.sum(); // 错误数
        double elapsedSec = elapsedMs / 1000.0;
        double throughput = elapsedSec > 0 ? total / elapsedSec : 0;

        System.out.println();
        System.out.println("=== 扫描完成 ===");
        System.out.println("─".repeat(50));
        System.out.printf("总记录数:   %,d%n", total);
        System.out.printf("文件数:     %,d%n", files);
        System.out.printf("目录数:     %,d%n", dirs);
        System.out.printf("累计大小:   %,.2f GB%n", size / (1024.0 * 1024.0 * 1024.0));
        System.out.printf("错误数:     %,d%n", errors);
        System.out.printf("耗时:       %.1fs%n", elapsedSec);
        System.out.printf("平均速度:   %,.0f 条/秒%n", throughput);
    }

    /**
     * 程序入口。
     *
     * @param args args[0]=扫描路径, args[1]=输出CSV, args[2]=并发数, args[3]=消费者线程数
     */
    public static void main(String[] args) {
        // 默认参数（测试用）
        args = new String[]{"\\\\MyNAS", "scan_result.csv", "500", "4"};

        if (args.length < 2) {
            System.out.println("用法: java -cp ntfs-scanner.jar ntfs.HighPerfNtfsScanner <扫描路径> <输出CSV> [并发数] [消费者线程数]");
            System.out.println("  示例: java -cp ntfs-scanner.jar ntfs.HighPerfNtfsScanner E:\\ scan.csv 500 4");
            return;
        }
        String root = args[0];
        String output = args[1];
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MAX_CONCURRENT;
        int consumerThreads = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_CONSUMER_THREADS;

        System.out.println("=== 高性能NTFS目录扫描器（虚拟线程版）===");
        System.out.println("扫描路径: " + root);
        System.out.println("输出文件: " + output);
        System.out.println("并发数:   " + concurrency);
        System.out.println("消费者线程数:   " + consumerThreads);
        System.out.println();

        HighPerfNtfsScanner scanner = new HighPerfNtfsScanner(concurrency, consumerThreads);
        try {
            scanner.scan(root, output);
        } catch (Exception e) {
            System.err.println("扫描失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
