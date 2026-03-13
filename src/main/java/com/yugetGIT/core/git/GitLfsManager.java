package com.yugetGIT.core.git;

import com.yugetGIT.config.StateProperties;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public final class GitLfsManager {

    private static final List<String> REQUIRED_PATTERNS = Arrays.asList(
        "staging/**/*.nbt",
        "staging/**/*.mca",
        "staging/screenshots/**/*.png",
        "meta/**/*.dat"
    );

    private GitLfsManager() {
    }

    public static void ensureConfigured(File repoDir) throws Exception {
        if (!StateProperties.isGitLfsAvailable()) {
            throw new RuntimeException("Git-LFS is not available. Run /backup status and install Git-LFS first.");
        }

        GitExecutor.GitResult versionResult = GitExecutor.execute(repoDir, 15, "lfs", "version");
        if (!versionResult.isSuccess()) {
            throw new RuntimeException("Git-LFS is not operational: " + versionResult.stderr);
        }

        GitExecutor.GitResult installResult = GitExecutor.execute(repoDir, 15, "lfs", "install", "--local");
        if (!installResult.isSuccess()) {
            throw new RuntimeException("Failed to initialize Git-LFS hooks: " + installResult.stderr);
        }

        if (!hasRequiredTrackingRules(repoDir)) {
            for (String pattern : REQUIRED_PATTERNS) {
                GitExecutor.GitResult trackResult = GitExecutor.execute(repoDir, 15, "lfs", "track", pattern);
                if (!trackResult.isSuccess()) {
                    throw new RuntimeException("Failed to track LFS pattern '" + pattern + "': " + trackResult.stderr);
                }
            }

            GitExecutor.GitResult addAttributesResult = GitExecutor.execute(repoDir, 10, "add", ".gitattributes");
            if (!addAttributesResult.isSuccess()) {
                throw new RuntimeException("Failed to stage .gitattributes: " + addAttributesResult.stderr);
            }
        }
    }

    public static boolean hasRequiredTrackingRules(File repoDir) {
        File attributesFile = new File(repoDir, ".gitattributes");
        if (!attributesFile.exists()) {
            return false;
        }

        try {
            String content = new String(Files.readAllBytes(attributesFile.toPath()), StandardCharsets.UTF_8);
            for (String pattern : REQUIRED_PATTERNS) {
                if (!content.contains(pattern)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
