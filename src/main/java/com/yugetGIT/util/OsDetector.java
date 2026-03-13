package com.yugetGIT.util;

public class OsDetector {
    public enum OS {
        WINDOWS, MACOS, LINUX, UNKNOWN
    }

    public enum Arch {
        X64, ARM64, UNKNOWN
    }

    public static OS detectOS() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return OS.WINDOWS;
        } else if (osName.contains("mac")) {
            return OS.MACOS;
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            return OS.LINUX;
        }
        return OS.UNKNOWN;
    }

    public static Arch detectArch() {
        String osArch = System.getProperty("os.arch").toLowerCase();
        if (osArch.equals("aarch64") || osArch.equals("arm64")) {
            return Arch.ARM64;
        } else if (osArch.contains("amd64") || osArch.contains("x86_64") || osArch.contains("x64")) {
            return Arch.X64;
        }
        return Arch.UNKNOWN;
    }
}