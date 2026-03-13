package com.yugetGIT.core.git;

import com.yugetGIT.util.OsDetector;
import com.yugetGIT.util.PlatformPaths;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class GitExecutor {

    public static class GitResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public GitResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public static GitResult execute(File workingDir, int timeoutSeconds, String... command) throws Exception {
        return executeWithInput(workingDir, timeoutSeconds, null, command);
    }

    public static GitResult executeWithInput(File workingDir, int timeoutSeconds, String stdin, String... command) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        // Use resolved git binary if available; otherwise rely on PATH
        String gitExe = GitBootstrap.isGitResolved() ? com.yugetGIT.config.StateProperties.getGitPath() : "git";
        cmdArgs.add(gitExe);
        for (String c : command) {
            cmdArgs.add(c);
        }

        ProcessBuilder pb = new ProcessBuilder(cmdArgs);
        if (workingDir != null) {
            pb.directory(workingDir);
        }

        Map<String, String> env = pb.environment();
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("HOME", System.getProperty("user.home"));

        // Inject our custom bin folder into PATH so Git finds our downloaded git-lfs automatically
        File binDir = PlatformPaths.getYugetGITDir().resolve("bin").toFile();
        if (binDir.exists() && binDir.isDirectory()) {
            String currentPath = env.getOrDefault("PATH", "");
            String pathSeparator = OsDetector.detectOS() == OsDetector.OS.WINDOWS ? ";" : ":";
            env.put("PATH", binDir.getAbsolutePath() + pathSeparator + currentPath);
        }




        if (OsDetector.detectOS() == OsDetector.OS.WINDOWS && GitBootstrap.isGitResolved()) {
            File gitDir = new File(com.yugetGIT.config.StateProperties.getGitPath()).getParentFile();
            if (gitDir != null && gitDir.getParentFile() != null) {
                File libexec = new File(gitDir.getParentFile().getParentFile(), "mingw64/libexec/git-core");
                if (libexec.exists()) {
                    env.put("GIT_EXEC_PATH", libexec.getAbsolutePath());
                }
            }
        }

        Process process = pb.start();

        if (stdin != null) {
            process.getOutputStream().write(stdin.getBytes());
            process.getOutputStream().close();
        }

        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Git command timed out after " + timeoutSeconds + " seconds: " + String.join(" ", command));
        }

        return new GitResult(process.exitValue(), stdout, stderr);
    }

    private static String readStream(java.io.InputStream is) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}