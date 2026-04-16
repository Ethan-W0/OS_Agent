package com.ran.cjb_agent.service.tools;

import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.service.ssh.SshService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 网络工具集（LangChain4j @Tool）
 * 覆盖：端口监听状态、网络连接、连通性测试
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NetworkTools {

    private final SshService sshService;
    private final OsProfileCache osProfileCache;

    @Tool("查询服务器当前正在监听的 TCP/UDP 端口列表，显示端口号、协议、监听地址和对应进程")
    public String listOpenPorts(
            @P("SSH连接ID") String sshConnectionId) {

        String cmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("portScan", "ss -tlnp"))
                .orElse("ss -tlnp");

        String result = sshService.execute(sshConnectionId, cmd, 15);
        return "【监听端口列表】\n" + result;
    }

    @Tool("查询当前所有网络连接状态（包括 ESTABLISHED、TIME_WAIT 等状态），可按端口或 IP 过滤")
    public String listNetworkConnections(
            @P("SSH连接ID") String sshConnectionId,
            @P("过滤关键字，例如端口号 80 或 IP 地址，留空显示所有连接") String filter) {

        String baseCmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("netConnections", "ss -antp"))
                .orElse("ss -antp");

        String cmd = baseCmd + (filter != null && !filter.isBlank() ? " | grep " + filter : "");
        String result = sshService.execute(sshConnectionId, cmd + " | head -50", 15);
        return "【网络连接状态】\n" + result;
    }

    @Tool("测试服务器到目标主机的网络连通性（ping 测试），返回延迟和丢包信息")
    public String checkConnectivity(
            @P("SSH连接ID") String sshConnectionId,
            @P("目标主机 IP 或域名，例如 8.8.8.8、baidu.com") String targetHost) {

        String cmd = "ping -c 4 -W 3 " + targetHost + " 2>&1";
        String result = sshService.execute(sshConnectionId, cmd, 20);
        return "【网络连通性测试 → " + targetHost + "】\n" + result;
    }

    @Tool("查询服务器的网络接口信息，包括 IP 地址、MAC 地址、接口状态等")
    public String getNetworkInterfaces(
            @P("SSH连接ID") String sshConnectionId) {

        String result = sshService.execute(sshConnectionId,
                "ip addr show 2>/dev/null || ifconfig 2>/dev/null", 10);
        return "【网络接口信息】\n" + result;
    }

    @Tool("查询服务器的路由表信息")
    public String getRoutingTable(
            @P("SSH连接ID") String sshConnectionId) {

        String result = sshService.execute(sshConnectionId,
                "ip route show 2>/dev/null || route -n 2>/dev/null", 10);
        return "【路由表】\n" + result;
    }
}
