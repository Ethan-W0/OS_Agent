package com.ran.cjb_agent.service.tools;

import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.service.ssh.SshService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 磁盘工具集（LangChain4j @Tool）
 * 覆盖：磁盘使用监测、目录占用分析
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiskTools {

    private final SshService sshService;
    private final OsProfileCache osProfileCache;

    @Tool("查询 Linux 服务器的磁盘使用情况，包括各挂载点的文件系统类型、总量、已用量、可用量和使用百分比")
    public String checkDiskUsage(
            @P("SSH连接ID，用于标识目标服务器") String sshConnectionId) {

        String cmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("diskUsage", "df -hT"))
                .orElse("df -hT");

        log.debug("查询磁盘使用情况 [{}]: {}", sshConnectionId, cmd);
        String result = sshService.execute(sshConnectionId, cmd, 15);
        return "【磁盘使用情况】\n" + result;
    }

    @Tool("查询指定目录下各子目录和文件的磁盘占用情况，按占用从大到小排序，帮助定位磁盘空间消耗来源")
    public String getTopDiskUsage(
            @P("SSH连接ID") String sshConnectionId,
            @P("要分析的目标目录路径，例如 /var、/home、/tmp，留空则分析根目录下一层") String directory) {

        String targetDir = (directory == null || directory.isBlank()) ? "/" : directory.trim();
        // 使用安全的方式执行 du（只读命令，直接放行）
        String cmd = "du -sh " + targetDir + "/* 2>/dev/null | sort -rh | head -20";

        log.debug("分析目录磁盘占用 [{}]: {}", sshConnectionId, cmd);
        String result = sshService.execute(sshConnectionId, cmd, 30);
        return String.format("【目录 %s 磁盘占用（Top 20）】\n%s", targetDir, result);
    }

    @Tool("查询磁盘 inode（索引节点）使用情况，当 inode 耗尽时即使磁盘有空间也无法创建新文件")
    public String checkInodeUsage(
            @P("SSH连接ID") String sshConnectionId) {

        String cmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("diskInodes", "df -ih"))
                .orElse("df -ih");

        String result = sshService.execute(sshConnectionId, cmd, 10);
        return "【Inode 使用情况】\n" + result;
    }

    @Tool("查询服务器总体内存使用情况（物理内存 + 交换分区），显示已用、可用、缓存等信息")
    public String checkMemoryUsage(
            @P("SSH连接ID") String sshConnectionId) {

        String result = sshService.execute(sshConnectionId, "free -h", 10);
        return "【内存使用情况】\n" + result;
    }
}
