import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

public class Base64Decode {
    public static void main(String[] args) {
        // 用法：java Base64Decode <输入Base64文件> <输出文件>
        if (args.length < 2) {
            System.err.println("用法: java Base64Decode <输入Base64文件> <输出文件>");
            System.err.println("示例: java Base64Decode base64.txt pg_bigm.dll");
            System.exit(1);
        }

        try {
            Path inputPath = Paths.get(args[0]);
            Path outputPath = Paths.get(args[1]);

            // 1. 读取 Base64 文本（支持换行和空格）
            String base64Content = Files.readString(inputPath).replaceAll("\\s", "");

            // 2. 解码为字节数组
            byte[] decodedBytes = Base64.getDecoder().decode(base64Content);

            // 3. 写入输出文件
            Files.write(outputPath, decodedBytes);

            System.out.println("✅ 解码成功！");
            System.out.println("   输入文件: " + inputPath.toAbsolutePath());
            System.out.println("   输出文件: " + outputPath.toAbsolutePath());
            System.out.println("   文件大小: " + decodedBytes.length + " 字节");

        } catch (IllegalArgumentException e) {
            System.err.println("❌ 解码失败：Base64 文本格式无效，请检查是否有误。");
            System.err.println("   错误详情: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("❌ 文件读写失败: " + e.getMessage());
        }
    }
}