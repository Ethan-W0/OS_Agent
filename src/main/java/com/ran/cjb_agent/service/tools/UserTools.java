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

    @Tool("创建新的普通用户账号。注意：此操作会修改系统配置，安全系统会请求确认")
    public String createUser(
            @P("SSH连接ID") String sshConnectionId,
            @P("新用户的用户名（只能包含字母、数字、下划线，长度 3-32 字符）") String username,
            @P("初始密码（可选，留空则创建无密码账号）") String password,
            @P("用户注释/全名（可选）") String comment) {

        // 用户名安全校验
        if (username == null || !username.matches("^[a-zA-Z][a-zA-Z0-9_-]{2,31}$")) {
            return "❌ 用户名格式无效：用户名必须以字母开头，只能包含字母、数字、下划线和连字符，长度 3-32 字符。";
        }

        // 检查用户是否已存在
        String checkCmd = "id " + username + " 2>/dev/null && echo EXISTS || echo NOT_EXISTS";
        String check = sshService.execute(sshConnectionId, checkCmd, 5);
        if (check.contains("EXISTS")) {
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

        // 注意：此命令会被 SecurityCheckNode（LangGraph4j 节点）中的 SecurityAssessmentAgent 评估
        // 通过安全评估后才会到达此执行点
        String createResult = sshService.execute(sshConnectionId, cmd.toString(), 15);

        // 设置密码
        if (password != null && !password.isBlank()) {
            String passwdCmd = "echo '" + username + ":" + password + "' | chpasswd";
            sshService.execute(sshConnectionId, passwdCmd, 10);
        }

        // 验证创建结果
        String verifyResult = sshService.execute(sshConnectionId, "id " + username + " 2>/dev/null", 5);
        if (verifyResult.contains("uid=")) {
            return String.format("✅ 用户 '%s' 创建成功！\n用户信息：%s\n%s",
                    username, verifyResult.trim(), createResult);
        }
        return "⚠️ 用户创建命令已执行，但验证时未找到用户，请手动确认：\n" + createResult;
    }

    @Tool("删除指定用户账号。注意：此操作不可撤销，安全系统会请求二次确认")
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

        String deleteCmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("deleteUser", "userdel -r"))
                .orElse("userdel -r");

        // 如果不删除主目录，去掉 -r 参数
        if (!removeHome) {
            deleteCmd = deleteCmd.replace(" -r", "").trim() + " " + username;
        } else {
            deleteCmd = deleteCmd + " " + username;
        }

        String result = sshService.execute(sshConnectionId, deleteCmd, 15);
        return String.format("✅ 用户 '%s' 已删除（主目录%s）。\n%s",
                username, removeHome ? "已同步删除" : "已保留", result);
    }
}
