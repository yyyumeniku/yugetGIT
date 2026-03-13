package com.yugetGIT.core.git;

import com.yugetGIT.util.ProgressParser;
import com.yugetGIT.util.BackgroundExecutor;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class PushOperation {

    public static void pushAsync(File repoDir, String remote, String branch, Consumer<String> onProgress, Consumer<Boolean> onComplete) {
        BackgroundExecutor.execute(() -> {
            if (!GitCredentialChecker.hasCredentialHelper()) {
                onProgress.accept("Push failed. Not configured — see /backup status");
                onComplete.accept(false);
                return;
            }

            try {
                List<String> cmdArgs = new ArrayList<>();
                String gitExe = GitBootstrap.isGitResolved() ? com.yugetGIT.config.StateProperties.getGitPath() : "git";
                cmdArgs.add(gitExe);
                cmdArgs.add("push");
                cmdArgs.add(remote);
                cmdArgs.add(branch);

                ProcessBuilder pb = new ProcessBuilder(cmdArgs);
                pb.directory(repoDir);
                pb.redirectErrorStream(true);

                Map<String, String> env = pb.environment();
                env.put("GIT_CONFIG_NOSYSTEM", "1");
                env.put("GIT_TERMINAL_PROMPT", "0");
                env.put("HOME", System.getProperty("user.home"));

                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ProgressParser.ParseResult pr = ProgressParser.parseLine(line);
                        if (pr != null) {
                            onProgress.accept("Pushing... " + pr.percentage + "% " + pr.details);
                        }
                    }
                }

                boolean success = process.waitFor() == 0;
                if (success) {
                    onProgress.accept("Push complete to " + remote + "/" + branch);
                } else {
                    onProgress.accept("Push failed.");
                }
                onComplete.accept(success);
            } catch (Exception e) {
                e.printStackTrace();
                onProgress.accept("Push encountered an exception.");
                onComplete.accept(false);
            }
        });
    }
}