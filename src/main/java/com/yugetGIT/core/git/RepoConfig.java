package com.yugetGIT.core.git;

import com.yugetGIT.util.OsDetector;

import java.io.File;

public class RepoConfig {

    public static void initAndConfigure(File repoDir) throws Exception {
        GitExecutor.GitResult initResult = GitExecutor.execute(repoDir.getParentFile(), 15, "init", repoDir.getName());
        if (!initResult.isSuccess()) {
            throw new RuntimeException("Failed to init git repository: " + initResult.stderr);
        }

        boolean useFsMonitor = OsDetector.detectOS() != OsDetector.OS.LINUX;
        
        GitExecutor.execute(repoDir, 5, "config", "core.fsmonitor", String.valueOf(useFsMonitor));
        GitExecutor.execute(repoDir, 5, "config", "core.untrackedCache", "true");
        GitExecutor.execute(repoDir, 5, "config", "core.preloadIndex", "true");
        GitExecutor.execute(repoDir, 5, "config", "core.bigFileThreshold", "512m");
        GitExecutor.execute(repoDir, 5, "config", "feature.manyFiles", "true");
        GitExecutor.execute(repoDir, 5, "config", "pack.windowMemory", "256m");
        GitExecutor.execute(repoDir, 5, "config", "pack.threads", "0");
        GitExecutor.execute(repoDir, 5, "config", "pack.compression", "9");
        GitExecutor.execute(repoDir, 5, "config", "gc.auto", "256");
        GitExecutor.execute(repoDir, 5, "config", "gc.autoPackLimit", "20");
        GitExecutor.execute(repoDir, 5, "config", "diff.algorithm", "histogram");
        GitExecutor.execute(repoDir, 5, "config", "index.version", "4");

        GitExecutor.execute(repoDir, 5, "update-index", "--index-version", "4");
        ensureOperationalConfig(repoDir);
    }

    public static void ensureOperationalConfig(File repoDir) throws Exception {
        GitLfsManager.ensureConfigured(repoDir);
    }
}