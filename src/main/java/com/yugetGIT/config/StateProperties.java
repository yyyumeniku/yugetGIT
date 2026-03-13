package com.yugetGIT.config;

import com.yugetGIT.util.PlatformPaths;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class StateProperties {
    private static boolean firstRun = true;
    private static String gitPath = "";
    private static String gitVersion = "";
    private static boolean gitLfsAvailable = false;
    private static boolean backupsEnabled = true;

    private static final Path STATE_FILE = PlatformPaths.getStatePropertiesPath();

    static {
        load();
    }

    public static void load() {
        if (!Files.exists(STATE_FILE)) {
            firstRun = true;
            save(); // Create with defaults
            return;
        }

        try (FileInputStream in = new FileInputStream(STATE_FILE.toFile())) {
            Properties props = new Properties();
            props.load(in);

            firstRun = Boolean.parseBoolean(props.getProperty("firstRun", "true"));
            gitPath = props.getProperty("gitPath", "");
            gitVersion = props.getProperty("gitVersion", "");
            gitLfsAvailable = Boolean.parseBoolean(props.getProperty("gitLfsAvailable", "false"));
            backupsEnabled = Boolean.parseBoolean(props.getProperty("backupsEnabled", "true"));
        } catch (IOException e) {
            System.err.println("[yugetGIT] Failed to load state.properties");
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            if (!Files.exists(STATE_FILE.getParent())) {
                Files.createDirectories(STATE_FILE.getParent());
            }

            Properties props = new Properties();
            props.setProperty("firstRun", String.valueOf(firstRun));
            props.setProperty("gitPath", gitPath);
            props.setProperty("gitVersion", gitVersion);
            props.setProperty("gitLfsAvailable", String.valueOf(gitLfsAvailable));
            props.setProperty("backupsEnabled", String.valueOf(backupsEnabled));

            try (FileOutputStream out = new FileOutputStream(STATE_FILE.toFile())) {
                props.store(out, "yugetGIT persistent state");
            }
        } catch (IOException e) {
            System.err.println("[yugetGIT] Failed to save state.properties");
            e.printStackTrace();
        }
    }

    public static boolean isFirstRun() {
        return firstRun;
    }

    public static void setFirstRun(boolean firstRun) {
        StateProperties.firstRun = firstRun;
        save();
    }

    public static String getGitPath() {
        return gitPath;
    }

    public static void setGitPath(String gitPath) {
        StateProperties.gitPath = gitPath;
        save();
    }

    public static String getGitVersion() {
        return gitVersion;
    }

    public static void setGitVersion(String gitVersion) {
        StateProperties.gitVersion = gitVersion;
        save();
    }

    public static boolean isGitLfsAvailable() {
        return gitLfsAvailable;
    }

    public static void setGitLfsAvailable(boolean gitLfsAvailable) {
        StateProperties.gitLfsAvailable = gitLfsAvailable;
        save();
    }

    public static boolean isBackupsEnabled() {
        return backupsEnabled;
    }

    public static void setBackupsEnabled(boolean backupsEnabled) {
        StateProperties.backupsEnabled = backupsEnabled;
        save();
    }
}