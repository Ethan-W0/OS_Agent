package com.ran.cjb_agent.service.tools;

import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.service.ssh.SshService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户管理工具集（LangChain4j @Tool）
 * 覆盖：用户创建、删除、查询（高危操作，经安全拦截层处理）
 * 所有写操作通过 executeWithSudo 以 sudo -S 方式执行，不依赖 TTY。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserTools {

    private final SshService sshService;
    private final OsProfileCache osProfileCache;

    @Tool("列出服务器上所有可登录的普通用户账号（排除系统账号）")
    public String listUsers(
            @P("SSH连接ID") String sshConnectionId) {

        String cmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("listUsers",
                        "cat /etc/passwd | grep -v nologin | grep -v false | awk -F: '{print $1}'"))
                .orElse("cat /etc/passwd | awk -F: '$7 !~ /nologin|false/ {print $1, $3, $6}'");

        String result = sshService.execute(sshConnectionId, cmd, 10);
        return "【系统用户列表】\n" + result;
    }

    @Tool("查询指定用户的详细信息，包括 UID、所属组、主目录、登录 Shell 等")
    public String getUserInfo(
            @P("SSH连接ID") String sshConnectionId,
            @P("用户名") String username) {

        String cmd = "id " + username + " 2>/dev/null && getent passwd " + username + " 2>/dev/null";
        String result = sshService.execute(sshConnectionId, cmd, 10);
        return "【用户信息：" + username + "】\n" + result;
    }

    @Tool("创建新的普通用户账号，并可选设置初始密码。此操作需要 sudo 权限。")
    public String createUser(
            @P("SSH连接ID") String sshConnectionId,
            @P("新用户的用户名（只能包含字母、数字、下划线，长度 3-32 字符）") String username,
            @P("初始密码（可选，留空则创建无密码账号）") String password,
            @P("用户注释/全名（可选）") String comment) {

        // 用户名安全校验
        if (username == null || !username.matches("^[a-zA-Z][a-zA-Z0-9_-]{2,31}$")) {
            return "❌ 用户名格式无效：用户名必须以字母开头，只能包含字母、数字、下划线和连字符，长度 3-32 字符。";
        }

        // 检查用户是否已存在（用 uid= 判断，避免 "NOT_EXISTS".contains("EXISTS") 误判）
        String idCheck = sshService.execute(sshConnectionId, "id " + username + " 2>/dev/null", 5);
        if (idCheck.contains("uid=")) {
            return "⚠️ 用户 '" + username + "' 已存在，无需重复创建。";
        }

        String createBaseCmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("createUser", "useradd -m -s /bin/bash"))
                .orElse("useradd -m -s /bin/bash");

        StringBuilder cmd = new StringBuilder(createBaseCmd);
        if (comment != null && !comment.isBlank()) {
            cmd.append(" -c '").append(comment.trim()).append("'");
        }
        cmd.append(" ").append(username);

        // useradd requires root → use sudo -S
        String createResult = sshService.executeWithSudo(sshConnectionId, cmd.toString(), 15);

        // 设置密码：chpasswd 读取 "user:passwd" 格式（stdin），不需要 TTY
        if (password != null && !password.isBlank()) {
            String escapedUser = username.replace("'", "'\\''");
            String escapedPwd  = password.replace("'", "'\\''");
            // executeWithSudo will prepend "echo <sshPwd> | sudo -S -p ''" so we pass
            // the inner command as: bash -c "echo 'user:pwd' | chpasswd"
            sshService.executeWithSudo(sshConnectionId,
                    "bash -c \"printf '%s:%s\\n' '" + escapedUser + "' '" + escapedPwd + "' | chpasswd\"", 10);
        }

        // 验证创建结果
        String verifyResult = sshService.execute(sshConnectionId, "id " + username + " 2>/dev/null", 5);
        if (verifyResult.contains("uid=")) {
            return String.format("✅ 用户 '%s' 创建成功！\n用户信息：%s\n执行详情：%s",
                    username, verifyResult.trim(), createResult.isBlank() ? "（无输出）" : createResult);
        }
        return "⚠️ 用户创建命令已执行，但验证时未找到用户，请手动确认：\n" + createResult;
    }

    @Tool("删除指定用户账号。此操作不可撤销，需要 sudo 权限。")
    public String deleteUser(
            @P("SSH连接ID") String sshConnectionId,
            @P("要删除的用户名") String username,
            @P("是否同时删除用户主目录和邮件文件：true（删除，默认）/ false（保留）") boolean removeHome) {

        // 安全校验：不允许删除 root 或系统关键用户
        if ("root".equals(username) || "nobody".equals(username) || "daemon".equals(username)) {
            return "❌ 拒绝操作：禁止删除系统关键账号 '" + username + "'。";
        }

        // 检查用户是否存在
        String checkResult = sshService.execute(sshConnectionId, "id " + username + " 2>/dev/null", 5);
        if (!checkResult.contains("uid=")) {
            return "⚠️ 用户 '" + username + "' 不存在，无需删除。";
        }

        String baseDeleteCmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("deleteUser", "userdel"))
                .orElse("userdel");

        String deleteCmd;
        if (removeHome) {
            deleteCmd = baseDeleteCmd + " -r " + username;
        } else {
            deleteCmd = baseDeleteCmd.replace("-r", "").trim() + " " + username;
        }

        // userdel requires root → sudo -S
        String result = sshService.executeWithSudo(sshConnectionId, deleteCmd, 15);
        return String.format("✅ 用户 '%s' 已删除（主目录%s）。\n%s",
                username, removeHome ? "已同步删除" : "已保留", result);
    }

    @Tool("修改指定用户的登录密码。此操作需要 sudo 权限。")
    public String changePassword(
            @P("SSH连接ID") String sshConnectionId,
            @P("要修改密码的用户名") String username,
            @P("新密码") String newPassword) {

        if (username == null || username.isBlank() || newPassword == null || newPassword.isBlank()) {
            return "❌ 用户名和新密码不能为空。";
        }
        if ("root".equals(username)) {
            return "❌ 禁止通过 Agent 修改 root 密码。";
        }

        String escapedUser = username.replace("'", "'\\''");
        String escapedPwd  = newPassword.replace("'", "'\\''");
        // chpasswd reads "user:password" from stdin — no TTY needed
        String result = sshService.executeWithSudo(sshConnectionId,
                "bash -c \"printf '%s:%s\\n' '" + escapedUser + "' '" + escapedPwd + "' | chpasswd\"", 10);

        if (result.contains("⚠️")) {
            return "❌ 密码修改失败：" + result;
        }
        return "✅ 用户 '" + username + "' 的密码已成功修改。";
    }
}
