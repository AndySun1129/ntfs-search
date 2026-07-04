package ntfs;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinBase.WIN32_FIND_DATA;
import com.sun.jna.platform.win32.WinNT;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高性能NTFS目录扫描器（基于 Windows FindFirstFile/FindNextFile API + 虚拟线程）。
 *
 * <p>无需管理员权限，适用于超大磁盘（200TB级）流式扫描。核心特性：
 * <ul>
 *   <li>生产者-消费者模式 + 有界阻塞队列，实现背压控制，避免OOM</li>
 *   <li>虚拟线程递归遍历目录，信号量限制并发数</li>
 *   <li>FindFirstFile/FindNextFile 一次调用即获取全部属性（大小/时间/类型），无需额外 GetFileAttributesEx</li>
 *   <li>FileInfo 使用 record 值对象，时间戳存原始 FILETIME long，输出时再格式化</li>
 *   <li>批量CSV写入（每批5000条），64KB缓冲</li>
 * </ul>
 */
public class HighPerfNtfsScanner {

    // ===== 配置参数 =====
    /** 生产者-消费者队列容量（背压控制） */
    private static final int QUEUE_CAPACITY = 10_000;
    /** CSV批量写入大小 */
    private static final int BATCH_SIZE = 5_000;
    /** 默认最大并发目录遍历数 */
    private static final int DEFAULT_MAX_CONCURRENT = 500;
    /** 进度输出间隔（条数） */
    private static final long PROGRESS_INTERVAL = 100_000;
    /** CSV缓冲区大小 */
    private static final int IO_BUFFER_SIZE = 64 * 1024;

    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_ACCESS_DENIED = 5;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // ===== 共享状态 =====
    private final BlockingQueue<FileInfo> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong fileCount = new AtomicLong();
    private final AtomicLong dirCount = new AtomicLong();
    private final AtomicLong totalSize = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    /** 记录ID自增计数器（类似MFT记录号），根目录为0 */
    private final AtomicLong idCounter = new AtomicLong(0);
    /** 活跃生产者任务计数，归零表示所有目录遍历完成 */
    private final AtomicLong pendingTasks = new AtomicLong(0);
    /** 信号量限制并发目录遍历数，避免线程爆炸 */
    private final Semaphore semaphore;
    private volatile boolean producersDone = false;
    private long scanStart;
    /** 扫描根路径（以反斜杠结尾），用于为 parentId=0 的条目填充 searchPrefix */
    private String rootPath;

    /**
     * FileInfo 值对象（Record），减少对象头开销。
     * 使用 id + parentId 替代完整路径（类似NTFS MFT记录号），存储数据库可大幅节省空间。
     * parentId=0 的条目（根目录直接子项）将搜索前缀直接拼在 name 上，便于独立还原完整路径；
     * 更深层条目 name 仅为文件名，路径可由 parentId 链向上回溯重建。
     * 时间戳存原始 FILETIME，输出时再格式化。
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

    public HighPerfNtfsScanner(int concurrency) {
        this.semaphore = new Semaphore(Math.max(1, concurrency));
    }

    public HighPerfNtfsScanner() {
        this(DEFAULT_MAX_CONCURRENT);
    }

    /** 扫描入口 */
    public void scan(String root, String output) throws Exception {
        String normalizedRoot = normalizeRoot(root);
        // 确保 rootPath 以反斜杠结尾，便于 parentId=0 的条目直接拼接: fullPath = searchPrefix + name
        this.rootPath = normalizedRoot.endsWith("\\") ? normalizedRoot : normalizedRoot + "\\";
        scanStart = System.currentTimeMillis();

        System.out.println("开始扫描: " + normalizedRoot);
        System.out.println("输出文件: " + output);
        System.out.println();

        // 启动消费者线程（单线程批量写CSV）
        Thread consumer = new Thread(new CsvWriter(output), "csv-writer");
        consumer.start();

        // 启动生产者：根目录遍历（虚拟线程）
        // 根目录分配ID=0，parentId=-1（无父级）
        long rootId = idCounter.getAndIncrement();
        pendingTasks.set(1);
        Thread.startVirtualThread(() -> traverseDirectory(normalizedRoot, rootId, true));

        // 等待消费者完成（所有生产者结束后，消费者排空队列并退出）
        consumer.join();

        long elapsed = System.currentTimeMillis() - scanStart;
        printSummary(elapsed);
    }

    // ===== 生产者：目录遍历 =====

    /** 遍历单个目录（每个目录一个虚拟线程任务） */
    private void traverseDirectory(String dirPath, long dirId, boolean isRoot) {
        try {
            semaphore.acquire();
            try {
                enumerateDirectory(dirPath, dirId, isRoot);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            errorCount.incrementAndGet();
            System.err.println("遍历目录失败: " + dirPath + " -> " + t.getMessage());
        } finally {
            // 活跃任务归零时通知消费者结束
            if (pendingTasks.decrementAndGet() == 0) {
                producersDone = true;
            }
        }
    }

    /** 使用 FindFirstFile/FindNextFile 枚举目录内容 */
    private void enumerateDirectory(String dirPath, long dirId, boolean isRoot) {
        String pattern = joinPath(dirPath, "*");
        WIN32_FIND_DATA data = new WIN32_FIND_DATA();
        // jna-platform Kernel32.FindFirstFile 形参为 Pointer，需传入结构体指针并手动 read()
        WinNT.HANDLE hFind = Kernel32.INSTANCE.FindFirstFile(pattern, data.getPointer());

        if (hFind == null || hFind.equals(WinBase.INVALID_HANDLE_VALUE)) {
            int err = Kernel32.INSTANCE.GetLastError();
            errorCount.incrementAndGet();
            if (err == ERROR_ACCESS_DENIED) {
                System.err.println("权限拒绝: " + dirPath);
            } else if (err != ERROR_FILE_NOT_FOUND) {
                System.err.println("FindFirstFile 失败: " + dirPath + " (错误码=" + err + ")");
            }
            return;
        }

        try {
            data.read();
            while (true) {
                String name = Native.toString(data.cFileName);
                if (name.equals(".")) {
                    // 根目录通过 "." 条目输出自身记录（parentId=-1，无父级）
                    if (isRoot) {
                        long size = ((long) data.nFileSizeHigh << 32) | (data.nFileSizeLow & 0xFFFFFFFFL);
                        long createTime = fileTimeToLong(data.ftCreationTime);
                        long modifyTime = fileTimeToLong(data.ftLastWriteTime);
                        queue.put(new FileInfo(dirId, -1L, sanitize(dirPath), "", (byte) 1, size, createTime, modifyTime));
                        long c = fileCount.incrementAndGet();
                        dirCount.incrementAndGet();
                        if (c % PROGRESS_INTERVAL == 0) {
                            printProgress(c);
                        }
                    }
                } else if (!name.equals("..")) {
                    processEntry(dirPath, dirId, name, data);
                }
                if (!Kernel32.INSTANCE.FindNextFile(hFind, data.getPointer())) {
                    break;
                }
                data.read();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Kernel32.INSTANCE.FindClose(hFind);
        }
    }

    /** 处理单个条目：分配ID并放入队列，必要时递归子目录 */
    private void processEntry(String parentPath, long parentId, String name, WIN32_FIND_DATA data) throws InterruptedException {
        int attr = data.dwFileAttributes;
        boolean isDir = (attr & FILE_ATTRIBUTE_DIRECTORY) != 0;
        boolean isReparse = (attr & FILE_ATTRIBUTE_REPARSE_POINT) != 0;

        String fullName = joinPath(parentPath, name);
        long size = ((long) data.nFileSizeHigh << 32) | (data.nFileSizeLow & 0xFFFFFFFFL);
        long createTime = fileTimeToLong(data.ftCreationTime);
        long modifyTime = fileTimeToLong(data.ftLastWriteTime);

        String ext = isDir ? "" : extractExtension(name);
        String safeName = sanitize(name);
        // parentId=0 表示父级是根目录，将搜索前缀直接拼在文件名上，便于独立还原完整路径
        if (parentId == 0) {
            safeName = rootPath + safeName;
        }

        // 分配唯一记录ID（类似MFT记录号），parentId 指向所属目录
        long id = idCounter.getAndIncrement();
        queue.put(new FileInfo(id, parentId, safeName, ext, (byte) (isDir ? 1 : 0), size, createTime, modifyTime));

        long c = fileCount.incrementAndGet();
        if (isDir) {
            dirCount.incrementAndGet();
        }
        totalSize.addAndGet(size);

        // 进度监控（无锁 AtomicLong）
        if (c % PROGRESS_INTERVAL == 0) {
            printProgress(c);
        }

        // 目录且非重解析点：递归遍历（新虚拟线程），子目录的 parentId 为当前条目 id
        // 重解析点（符号链接/挂载点）跳过递归，避免死循环
        if (isDir && !isReparse) {
            pendingTasks.incrementAndGet();
            final String childPath = fullName;
            Thread.startVirtualThread(() -> traverseDirectory(childPath, id, false));
        }
    }

    // ===== 消费者：批量写CSV =====

    private class CsvWriter implements Runnable {
        private final String outputPath;
        private final List<FileInfo> batch = new ArrayList<>(BATCH_SIZE);

        CsvWriter(String outputPath) {
            this.outputPath = outputPath;
        }

        @Override
        public void run() {
            try (FileOutputStream fos = new FileOutputStream(outputPath);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw, IO_BUFFER_SIZE)) {

                // 写入 UTF-8 BOM
                fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

                // 表头
                writer.write("记录ID,父记录ID,文件名,扩展名,是否文件夹,文件大小,创建时间,修改时间");
                writer.newLine();

                while (true) {
                    FileInfo info = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (info != null) {
                        batch.add(info);
                        // 尽量批量获取，填满批次
                        queue.drainTo(batch, BATCH_SIZE - batch.size());
                        if (batch.size() >= BATCH_SIZE) {
                            flushBatch(writer);
                        }
                    } else {
                        // 超时无数据，检查是否所有生产者已完成且队列已空
                        if (producersDone && queue.isEmpty()) {
                            break;
                        }
                    }
                }
                flushBatch(writer);
                writer.flush();
            } catch (IOException e) {
                System.err.println("CSV写入失败: " + e.getMessage());
                e.printStackTrace();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** 批量格式化为CSV行，使用StringBuilder一次性写入，减少IO次数 */
        private void flushBatch(BufferedWriter writer) throws IOException {
            if (batch.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder(batch.size() * 128);
            for (FileInfo info : batch) {
                sb.append(info.id())
                        .append(',')
                        .append(info.parentId())
                        .append(',')
                        .append(quote(info.name()))
                        .append(',')
                        .append(quote(info.ext()))
                        .append(',')
                        .append(info.isDir())
                        .append(',')
                        .append(info.size())
                        .append(',')
                        .append(quote(formatFileTime(info.createTime())))
                        .append(',')
                        .append(quote(formatFileTime(info.modifyTime())))
                        .append('\n');
            }
            writer.write(sb.toString());
            batch.clear();
        }
    }

    // ===== 工具方法 =====

    /** CSV字段双引号包裹，内部双引号转义为两个 */
    static String quote(String s) {
        if (s == null || s.isEmpty()) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') {
                sb.append("\"\"");
            } else {
                sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** FILETIME (低32位+高32位) 转为 long */
    static long fileTimeToLong(WinNT.FILETIME ft) {
        if (ft == null) {
            return 0L;
        }
        return ((long) ft.dwHighDateTime << 32) | (ft.dwLowDateTime & 0xFFFFFFFFL);
    }

    /** FILETIME (1601年以来的100纳秒) 格式化为本地时间字符串 */
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

    /** 提取扩展名（小写），无扩展名或目录返回空串 */
    static String extractExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot + 1).toLowerCase();
        }
        return "";
    }

    /** 路径拼接，避免盘根出现双反斜杠 */
    static String joinPath(String parent, String child) {
        if (parent.endsWith("\\")) {
            return parent + child;
        }
        return parent + "\\" + child;
    }

    /** 规范化扫描根路径 */
    static String normalizeRoot(String root) {
        root = root.trim();
        if (root.startsWith("\"") && root.endsWith("\"") && root.length() >= 2) {
            root = root.substring(1, root.length() - 1);
        }
        // 去除尾部多余反斜杠（保留盘根 "E:\"）
        while (root.length() > 3 && root.endsWith("\\")) {
            root = root.substring(0, root.length() - 1);
        }
        // "E:" -> "E:\"
        if (root.length() == 2 && root.charAt(1) == ':') {
            root = root + "\\";
        }
        return root;
    }

    /** 过滤文件名中的控制字符，防止破坏CSV格式 */
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

    static boolean isControlChar(char ch) {
        return (ch >= 0x00 && ch <= 0x1F)
                || ch == 0x7F
                || (ch >= 0x80 && ch <= 0x9F)
                || ch == 0x2028
                || ch == 0x2029
                || ch == 0xFFFD
                || ch == 0xFEFF
                || (ch >= 0xFFF9 && ch <= 0xFFFB);
    }

    private void printProgress(long count) {
        long size = totalSize.get();
        long elapsedMs = System.currentTimeMillis() - scanStart;
        double elapsedSec = elapsedMs / 1000.0;
        double throughput = elapsedSec > 0 ? count / elapsedSec : 0;
        System.out.printf("进度: %,d 条 | 累计大小: %,.2f GB | 速度: %,.0f 条/秒 | 已用: %.0fs%n",
                count, size / (1024.0 * 1024.0 * 1024.0), throughput, elapsedSec);
    }

    private void printSummary(long elapsedMs) {
        long total = fileCount.get();
        long dirs = dirCount.get();
        long files = total - dirs;
        long size = totalSize.get();
        long errors = errorCount.get();
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

    public static void main(String[] args) {
        args = new String[2];
        args[0] = "E:\\";
        args[1] = "scan_result.csv";

        if (args.length < 2) {
            System.out.println("用法: java -cp ntfs-scanner.jar ntfs.HighPerfNtfsScanner <扫描路径> <输出CSV> [并发数]");
            System.out.println("  示例: java -cp ntfs-scanner.jar ntfs.HighPerfNtfsScanner E:\\ scan.csv 500");
            System.out.println("  无需管理员权限");
            return;
        }
        String root = args[0];
        String output = args[1];
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MAX_CONCURRENT;

        System.out.println("=== 高性能NTFS目录扫描器（虚拟线程版）===");
        System.out.println("扫描路径: " + root);
        System.out.println("输出文件: " + output);
        System.out.println("并发数:   " + concurrency);
        System.out.println();

        HighPerfNtfsScanner scanner = new HighPerfNtfsScanner(concurrency);
        try {
            scanner.scan(root, output);
        } catch (Exception e) {
            System.err.println("扫描失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
