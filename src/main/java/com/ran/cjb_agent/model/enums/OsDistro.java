package com.ran.cjb_agent.model.enums;

/**
 * Linux 发行版枚举
 */
public enum OsDistro {
    OPEN_EULER("openEuler", "dnf"),
    CENTOS("CentOS", "yum"),
    RHEL("Red Hat Enterprise Linux", "dnf"),
    UBUNTU("Ubuntu", "apt"),
    DEBIAN("Debian", "apt"),
    ROCKY("Rocky Linux", "dnf"),
    ALMA("AlmaLinux", "dnf"),
    UNKNOWN("Unknown", "apt");

    private final String displayName;
    private final String defaultPackageManager;

    OsDistro(String displayName, String defaultPackageManager) {
        this.displayName = displayName;
        this.defaultPackageManager = defaultPackageManager;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultPackageManager() {
        return defaultPackageManager;
    }
}
