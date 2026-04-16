package com.ran.cjb_agent.service.os;

import com.ran.cjb_agent.model.enums.OsDistro;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 发行版命令映射器
 * 依据探测到的 Linux 发行版，返回该发行版推荐的原生命令路径
 * 避免因环境差异（如 openEuler 用 dnf，Ubuntu 用 apt）导致操作失败
 */
@Component
public class DistroCommandMapper {

    /**
     * 各发行版命令映射表（key: 操作类型，value: 推荐命令）
     */
    private static final Map<OsDistro, Map<String, String>> DISTRO_MAPS;

    static {
        Map<OsDistro, Map<String, String>> maps = new HashMap<>();

        // ===== openEuler（华为开源，基于 RHEL，用 dnf）=====
        Map<String, String> openEuler = new HashMap<>();
        openEuler.put("packageManager",   "dnf");
        openEuler.put("install",          "dnf install -y");
        openEuler.put("remove",           "dnf remove -y");
        openEuler.put("update",           "dnf update -y");
        openEuler.put("search",           "dnf search");
        openEuler.put("serviceManager",   "systemctl");
        openEuler.put("diskUsage",        "df -hT");
        openEuler.put("diskInodes",       "df -ih");
        openEuler.put("dirSize",          "du -sh");
        openEuler.put("processView",      "ps aux --sort=-%cpu | head -20");
        openEuler.put("processTop",       "top -bn1 | head -20");
        openEuler.put("portScan",         "ss -tlnp");
        openEuler.put("netConnections",   "ss -antp");
        openEuler.put("createUser",       "useradd -m -s /bin/bash");
        openEuler.put("deleteUser",       "userdel -r");
        openEuler.put("listUsers",        "cat /etc/passwd | grep -v nologin | grep -v false | awk -F: '{print $1}'");
        openEuler.put("firewall",         "firewall-cmd");
        openEuler.put("selinux",          "getenforce");
        openEuler.put("logPath",          "/var/log");
        openEuler.put("syslog",           "journalctl -n 50 --no-pager");
        maps.put(OsDistro.OPEN_EULER, Collections.unmodifiableMap(openEuler));

        // ===== CentOS 7/8（yum 或 dnf）=====
        Map<String, String> centos = new HashMap<>();
        centos.put("packageManager",   "yum");
        centos.put("install",          "yum install -y");
        centos.put("remove",           "yum remove -y");
        centos.put("update",           "yum update -y");
        centos.put("search",           "yum search");
        centos.put("serviceManager",   "systemctl");
        centos.put("diskUsage",        "df -hT");
        centos.put("diskInodes",       "df -ih");
        centos.put("dirSize",          "du -sh");
        centos.put("processView",      "ps aux --sort=-%cpu | head -20");
        centos.put("processTop",       "top -bn1 | head -20");
        centos.put("portScan",         "ss -tlnp");
        centos.put("netConnections",   "netstat -antp 2>/dev/null || ss -antp");
        centos.put("createUser",       "useradd -m -s /bin/bash");
        centos.put("deleteUser",       "userdel -r");
        centos.put("listUsers",        "cat /etc/passwd | grep -v nologin | grep -v false | awk -F: '{print $1}'");
        centos.put("firewall",         "firewall-cmd");
        centos.put("selinux",          "getenforce");
        centos.put("logPath",          "/var/log");
        centos.put("syslog",           "journalctl -n 50 --no-pager 2>/dev/null || tail -50 /var/log/messages");
        maps.put(OsDistro.CENTOS, Collections.unmodifiableMap(centos));

        // ===== RHEL（同 openEuler，用 dnf）=====
        maps.put(OsDistro.RHEL, Collections.unmodifiableMap(openEuler));

        // ===== Ubuntu / Debian（apt）=====
        Map<String, String> ubuntu = new HashMap<>();
        ubuntu.put("packageManager",   "apt");
        ubuntu.put("install",          "apt install -y");
        ubuntu.put("remove",           "apt remove -y");
        ubuntu.put("update",           "apt update && apt upgrade -y");
        ubuntu.put("search",           "apt search");
        ubuntu.put("serviceManager",   "systemctl");
        ubuntu.put("diskUsage",        "df -hT");
        ubuntu.put("diskInodes",       "df -ih");
        ubuntu.put("dirSize",          "du -sh");
        ubuntu.put("processView",      "ps aux --sort=-%cpu | head -20");
        ubuntu.put("processTop",       "top -bn1 | head -20");
        ubuntu.put("portScan",         "ss -tlnp");
        ubuntu.put("netConnections",   "ss -antp");
        ubuntu.put("createUser",       "adduser --disabled-password --gecos ''");
        ubuntu.put("deleteUser",       "deluser --remove-home");
        ubuntu.put("listUsers",        "getent passwd | grep -v nologin | grep -v false | awk -F: '{print $1}'");
        ubuntu.put("firewall",         "ufw");
        ubuntu.put("selinux",          "apparmor_status 2>/dev/null || echo 'SELinux not applicable'");
        ubuntu.put("logPath",          "/var/log");
        ubuntu.put("syslog",           "journalctl -n 50 --no-pager 2>/dev/null || tail -50 /var/log/syslog");
        maps.put(OsDistro.UBUNTU, Collections.unmodifiableMap(ubuntu));
        maps.put(OsDistro.DEBIAN, Collections.unmodifiableMap(ubuntu));

        // ===== Rocky / Alma（同 openEuler，用 dnf）=====
        maps.put(OsDistro.ROCKY, Collections.unmodifiableMap(openEuler));
        maps.put(OsDistro.ALMA, Collections.unmodifiableMap(openEuler));

        // ===== UNKNOWN（通用兜底）=====
        maps.put(OsDistro.UNKNOWN, Collections.unmodifiableMap(ubuntu));

        DISTRO_MAPS = Collections.unmodifiableMap(maps);
    }

    /**
     * 获取指定发行版的命令映射表
     */
    public Map<String, String> getCommandMap(OsDistro distro) {
        return DISTRO_MAPS.getOrDefault(distro, DISTRO_MAPS.get(OsDistro.UNKNOWN));
    }

    /**
     * 获取指定发行版的某个操作命令
     *
     * @param distro     发行版
     * @param operation  操作类型（如 "diskUsage"、"install"）
     * @param fallback   当映射不存在时的默认命令
     */
    public String getCommand(OsDistro distro, String operation, String fallback) {
        return getCommandMap(distro).getOrDefault(operation, fallback);
    }
}
