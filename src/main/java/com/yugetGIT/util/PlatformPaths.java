package com.yugetGIT.util;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PlatformPaths {
    
    // Always resolve relative to current working directory.
    // In Prism Launcher and Forge dev environments, this correctly points to the instance folder instead of the global OS user home.
    public static Path getMinecraftDir() {
        return Paths.get(".").toAbsolutePath().normalize();
    }

    public static Path getYugetGITDir() {
        return getMinecraftDir().resolve("yugetGIT");
    }

    public static Path getStatePropertiesPath() {
        return getYugetGITDir().resolve("state.properties");
    }

    public static Path getGitInstallDir() {
        return getYugetGITDir().resolve("git");
    }

    public static Path getWorldsDir() {
        return getYugetGITDir().resolve("worlds");
    }

    public static Path getRepoDir() {
        return getYugetGITDir().resolve("repo");
    }
}