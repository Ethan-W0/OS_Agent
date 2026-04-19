package com.ran.cjb_agent.service.tools;

import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.service.ssh.SshService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 软件包管理工具集（LangChain4j @Tool）
 * 自动适配目标服务器的包管理器（apt / yum / dnf / zypper 等）
 * 所有写操作通过 executeWithSudo 执行，支持 SUDO_REQUEST 密码弹框
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackageTools {

    private final SshService sshService;
    private final OsProfileCache osProfileCache;

    // ──────────────────────────────────────────────────────────────────
    // 内部：包管理器命令解析
    // ──────────────────────────────────────────────────────────────────

    /** 获取包管理器名称（apt / yum / dnf / zypper …） */
    private String getPkgManager(String connectionId) {
        return osProfileCache.get(connectionId)
                .map(p -> p.getCommandMap().getOrDefault("packageManager", "apt"))
                .orElse("apt");
    }

    /** 获取安装命令前缀（如 "apt-get install -y" / "yum install -y"） */
    private String getInstallCmd(String connectionId) {
        return osProfileCache.get(connectionId)
                .map(p -> p.getCommandMap().getOrDefault("install", "apt-get install -y"))
                .orElse("apt-get install -y");
    }

    /** 获取卸载命令前缀 */
    private String getRemoveCmd(String connectionId) {
        String pm = getPkgManager(connectionId);
        return switch (pm) {
            case "apt", "apt-get" -> "apt-get remove -y";
            case "yum"            -> "yum remove -y";
            case "dnf"            -> "dnf remove -y";
            case "zypper"         -> "zypper remove -y";
            default               -> pm + " remove -y";
        };
    }

    /** 获取更新包列表命令 */
    private String getUpdateCmd(String connectionId) {
        String pm = getPkgManager(connectionId);
        return switch (pm) {
            case "apt", "apt-get" -> "apt-get update -qq";
            case "yum"            -> "yum makecache -q";
            case "dnf"            -> "dnf makecache -q";
            case "zypper"         -> "zypper refresh";
            default               -> pm + " update";
        };
    }

    /** 构造包安装后的验证命令 */
    private String buildVerifyCmd(String packageName) {
        // 优先用 which 找可执行文件，再尝试 dpkg/rpm 查询
        String bin = packageName.split("[- .]")[0]; // nginx、mysql → nginx、mysql
        return String.format(
                "which %s 2>/dev/null || dpkg -l %s 2>/dev/null | grep '^ii' || rpm -q %s 2>/dev/null",
                bin, packageName, packageName
        );
    }

    // ──────────────────────────────────────────────────────────────────
    // 公开工具方法
    // ──────────────────────────────────────────────────────────────────

    @Tool("安装指定软件包到服务器。自动使用系统包管理器（apt/yum/dnf等），需要 sudo 权限。" +
          "安装前可选择是否刷新包列表索引（apt update 等）。安装完成后会验证是否成功。")
    public String installPackage(
            @P("SSH连接ID") String sshConnectionId,
            @P("要安装的软件包名称，例如 nginx、curl、vim、git、docker.io") String packageName,
            @P("是否在安装前先刷新包索引（apt update / yum makecache），建议首次安装时设为 true，默认 true") String updateFirst) {

        if (packageName == null || packageName.isBlank()) {
            return "❌ 请提供要安装的软件包名称。";
        }
        packageName = packageName.trim();

        boolean doUpdate = !"false".equalsIgnoreCase(updateFirst);
        String installCmd = getInstallCmd(sshConnectionId);
        String pm = getPkgManager(sshConnectionId);

        log.info("安装软件包 [{}]: {} via {}", sshConnectionId, packageName, pm);

        // Step 1: 刷新包列表（可选）
        if (doUpdate) {
            String updateCmd = getUpdateCmd(sshConnectionId);
            String updateResult = sshService.executeWithSudo(sshConnectionId, updateCmd, 60);
            log.debug("包列表刷新结果: {}", updateResult);
            if (updateResult.startsWith("⚠️") || updateResult.startsWith("❌")) {
                return "⚠️ 刷新包列表失败，安装终止：\n" + updateResult;
            }
        }

        // Step 2: 安装
        String fullInstallCmd = installCmd + " " + packageName;
        String installResult = sshService.executeWithSudo(sshConnectionId, fullInstallCmd, 180);

        // Step 3: 验证安装结果
        String verify = sshService.execute(sshConnectionId, buildVerifyCmd(packageName), 10);
        boolean success = !verify.isBlank()
                && !verify.startsWith("⚠️")
                && (verify.contains("/") || verify.contains("ii ") || verify.contains("is already installed"));

        if (success) {
            return String.format("✅ 软件包 **%s** 安装成功！\n\n**安装详情：**\n%s\n\n**验证结果：**\n%s",
                    packageName, truncate(installResult, 600), verify.trim());
        } else {
            return String.format("⚠️ 安装命令已执行，但验证失败，请手动确认：\n\n**安装输出：**\n%s",
                    truncate(installResult, 800));
        }
    }

    @Tool("卸载指定软件包。使用系统包管理器执行卸载操作，需要 sudo 权限。")
    public String removePackage(
            @P("SSH连接ID") String sshConnectionId,
            @P("要卸载的软件包名称") String packageName,
            @P("是否同时清除配置文件（true=彻底清除，false=仅卸载保留配置，默认 false）") String purge) {

        if (packageName == null || packageName.isBlank()) {
            return "❌ 请提供要卸载的软件包名称。";
        }
        packageName = packageName.trim();

        boolean doPurge = "true".equalsIgnoreCase(purge);
        String pm = getPkgManager(sshConnectionId);
        String removeCmd;

        if (doPurge && (pm.equals("apt") || pm.equals("apt-get"))) {
            removeCmd = "apt-get purge -y " + packageName + " && apt-get autoremove -y";
        } else {
            removeCmd = getRemoveCmd(sshConnectionId) + " " + packageName;
        }

        String result = sshService.executeWithSudo(sshConnectionId, removeCmd, 120);
        String action = doPurge ? "彻底卸载（含配置）" : "卸载";

        if (result.startsWith("⚠️") || result.startsWith("❌")) {
            return "❌ 卸载失败：\n" + result;
        }
        return String.format("✅ 软件包 **%s** 已%s。\n\n%s", packageName, action, truncate(result, 500));
    }

    @Tool("查询软件包是否已安装，并显示已安装版本信息。")
    public String checkPackage(
            @P("SSH连接ID") String sshConnectionId,
            @P("要查询的软件包名称，例如 nginx、python3、git") String packageName) {

        if (packageName == null || packageName.isBlank()) {
            return "❌ 请提供要查询的软件包名称。";
        }
        packageName = packageName.trim();

        // 综合查询：which + dpkg/rpm + 版本号
        String cmd = String.format(
                "{ dpkg -l '%s' 2>/dev/null | grep '^ii'; } || " +
                "{ rpm -qi '%s' 2>/dev/null | grep -E 'Name|Version|Summary'; } || " +
                "{ which '%s' 2>/dev/null && %s --version 2>&1 | head -3; } || " +
                "echo 'NOT_INSTALLED'",
                packageName, packageName, packageName.split("[- .]")[0], packageName.split("[- .]")[0]
        );

        String result = sshService.execute(sshConnectionId, cmd, 10);

        if (result.contains("NOT_INSTALLED") || result.isBlank()) {
            return String.format("📦 软件包 **%s** 未安装。\n可使用安装工具安装它。", packageName);
        }
        return String.format("📦 **%s** 已安装：\n%s", packageName, result.trim());
    }

    @Tool("搜索可用的软件包，返回匹配的包名和简要描述。")
    public String searchPackage(
            @P("SSH连接ID") String sshConnectionId,
            @P("搜索关键词，例如 nginx、mysql、python") String keyword) {

        if (keyword == null || keyword.isBlank()) {
            return "❌ 请提供搜索关键词。";
        }
        keyword = keyword.trim();

        String pm = getPkgManager(sshConnectionId);
        String searchCmd = switch (pm) {
            case "apt", "apt-get" -> "apt-cache search " + keyword + " | head -20";
            case "yum"            -> "yum search " + keyword + " 2>/dev/null | head -20";
            case "dnf"            -> "dnf search " + keyword + " 2>/dev/null | head -20";
            case "zypper"         -> "zypper search " + keyword + " 2>/dev/null | head -20";
            default               -> pm + " search " + keyword + " 2>/dev/null | head -20";
        };

        String result = sshService.execute(sshConnectionId, searchCmd, 30);
        if (result.isBlank()) {
            return "未找到与 \"" + keyword + "\" 相关的软件包。";
        }
        return String.format("🔍 搜索 \"%s\" 的结果（前20条）：\n\n%s", keyword, result);
    }

    @Tool("刷新服务器的软件包索引（apt update / yum makecache），安装前建议先刷新。")
    public String updatePackageIndex(
            @P("SSH连接ID") String sshConnectionId) {

        String updateCmd = getUpdateCmd(sshConnectionId);
        String result = sshService.executeWithSudo(sshConnectionId, updateCmd, 60);

        if (result.startsWith("⚠️") || result.startsWith("❌")) {
            return "❌ 刷新包索引失败：\n" + result;
        }
        return "✅ 包索引刷新完成。\n\n" + truncate(result, 400);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "\n…（输出过长已截断）" : s;
    }
}
