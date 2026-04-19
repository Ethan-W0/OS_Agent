package com.ran.cjb_agent.service.tools;

import com.ran.cjb_agent.service.ssh.SshService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文件工具集（LangChain4j @Tool）
 * 覆盖：文件搜索、目录列举、文件内容查看、文件创建与写入
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileTools {

    private final SshService sshService;

    @Tool("在指定目录下搜索文件，支持按文件名、类型、修改时间等条件筛选")
    public String findFiles(
            @P("SSH连接ID") String sshConnectionId,
            @P("搜索起始目录，例如 /var/log、/home") String directory,
            @P("文件名模式（支持通配符），例如 *.log、error*、nginx.conf，留空则搜索所有文件") String namePattern,
            @P("最大搜索深度，建议 3-5 层，留空不限制") String maxDepth) {

        StringBuilder cmd = new StringBuilder("find ");
        cmd.append(directory == null || directory.isBlank() ? "/tmp" : directory.trim());

        if (maxDepth != null && !maxDepth.isBlank()) {
            cmd.append(" -maxdepth ").append(maxDepth.trim());
        }

        if (namePattern != null && !namePattern.isBlank()) {
            cmd.append(" -name '").append(namePattern.trim()).append("'");
        }

        cmd.append(" 2>/dev/null | head -50");

        String result = sshService.execute(sshConnectionId, cmd.toString(), 20);
        return "【文件搜索结果】\n" + (result.isBlank() ? "未找到匹配文件。" : result);
    }

    @Tool("列出指定目录的文件和子目录，显示详细信息（权限、大小、修改时间等）")
    public String listDirectory(
            @P("SSH连接ID") String sshConnectionId,
            @P("要列举的目录路径，例如 /var/log、/etc/nginx") String directory) {

        String dir = (directory == null || directory.isBlank()) ? "~" : directory.trim();
        String result = sshService.execute(sshConnectionId, "ls -lah " + dir + " 2>/dev/null", 10);
        return String.format("【目录 %s 内容】\n%s", dir, result);
    }

    @Tool("查看文本文件内容，适合查看配置文件、日志等。大文件仅显示最后 100 行")
    public String readFileContent(
            @P("SSH连接ID") String sshConnectionId,
            @P("文件完整路径，例如 /etc/nginx/nginx.conf、/var/log/syslog") String filePath,
            @P("查看模式：head（前N行）、tail（后N行）、all（全部，仅适合小文件），默认 tail") String mode,
            @P("行数，默认 50") String lines) {

        if (filePath == null || filePath.isBlank()) {
            return "请提供要查看的文件路径。";
        }

        // 安全校验：不允许读取敏感文件
        if (filePath.contains("/etc/shadow") || filePath.contains("/etc/gshadow")) {
            return "⚠️ 出于安全考虑，禁止直接读取密码影子文件。";
        }

        int lineCount = 50;
        try {
            if (lines != null && !lines.isBlank()) {
                lineCount = Integer.parseInt(lines.trim());
                lineCount = Math.min(lineCount, 500); // 最多 500 行
            }
        } catch (NumberFormatException ignored) {}

        String cmd;
        if ("head".equalsIgnoreCase(mode)) {
            cmd = "head -n " + lineCount + " " + filePath + " 2>/dev/null";
        } else if ("all".equalsIgnoreCase(mode)) {
            cmd = "cat " + filePath + " 2>/dev/null | head -200";
        } else {
            cmd = "tail -n " + lineCount + " " + filePath + " 2>/dev/null";
        }

        String result = sshService.execute(sshConnectionId, cmd, 15);
        return String.format("【文件内容：%s】\n%s", filePath, result);
    }

    @Tool("查看文件的基本属性信息，包括大小、权限、所有者、修改时间等")
    public String getFileInfo(
            @P("SSH连接ID") String sshConnectionId,
            @P("文件或目录的完整路径") String filePath) {

        String result = sshService.execute(sshConnectionId,
                "stat " + filePath + " 2>/dev/null && ls -lah " + filePath + " 2>/dev/null", 10);
        return "【文件信息：" + filePath + "】\n" + result;
    }

    @Tool("创建一个新文件并写入指定内容。适用于生成配置、脚本、数据文件等场景。" +
          "若文件已存在会被覆盖（使用 overwrite=true），否则追加到文件末尾。")
    public String writeFile(
            @P("SSH连接ID") String sshConnectionId,
            @P("目标文件完整路径，例如 /home/user/test.txt、/tmp/config.yaml") String filePath,
            @P("要写入的文件内容") String content,
            @P("是否覆盖已有内容（true=覆盖，false=追加到末尾），默认 true") String overwrite) {

        if (filePath == null || filePath.isBlank()) {
            return "请提供目标文件路径。";
        }

        // Safety: block critical system files
        String[] forbidden = {"/etc/passwd", "/etc/shadow", "/etc/sudoers", "/boot/"};
        for (String f : forbidden) {
            if (filePath.startsWith(f)) {
                return "⚠️ 出于安全考虑，禁止写入关键系统文件：" + filePath;
            }
        }

        boolean append = "false".equalsIgnoreCase(overwrite);
        String redirect = append ? ">>" : ">";

        // Escape single quotes in content
        String escaped = content == null ? "" : content.replace("'", "'\\''");
        String cmd = "printf '%s\\n' '" + escaped + "' " + redirect + " " + filePath;

        String result = sshService.execute(sshConnectionId, cmd, 15);
        String action = append ? "追加" : "写入";
        String verify = sshService.execute(sshConnectionId, "cat " + filePath + " 2>/dev/null | head -20", 5);
        return String.format("✅ 已%s内容到文件 %s\n【文件当前内容（前20行）】\n%s",
                action, filePath, verify);
    }

    @Tool("创建目录（支持递归创建多级目录）")
    public String createDirectory(
            @P("SSH连接ID") String sshConnectionId,
            @P("要创建的目录路径，例如 /home/user/project/src") String dirPath) {

        if (dirPath == null || dirPath.isBlank()) {
            return "请提供目录路径。";
        }
        String result = sshService.execute(sshConnectionId, "mkdir -p " + dirPath + " && echo 'OK'", 10);
        if (result.contains("OK")) {
            return "✅ 目录已创建：" + dirPath;
        }
        return "创建目录结果：\n" + result;
    }

    @Tool("删除指定文件（不支持递归删除目录，避免误操作）")
    public String deleteFile(
            @P("SSH连接ID") String sshConnectionId,
            @P("要删除的文件完整路径") String filePath) {

        if (filePath == null || filePath.isBlank()) {
            return "请提供文件路径。";
        }
        // Block system critical paths
        if (filePath.equals("/") || filePath.startsWith("/etc/") || filePath.startsWith("/bin/")
                || filePath.startsWith("/usr/") || filePath.startsWith("/boot/")) {
            return "⚠️ 禁止删除系统目录下的文件，请手动操作并确认后再执行。";
        }
        String result = sshService.execute(sshConnectionId, "rm -f " + filePath + " && echo 'OK'", 10);
        if (result.contains("OK")) {
            return "✅ 文件已删除：" + filePath;
        }
        return "删除结果：\n" + result;
    }
}
