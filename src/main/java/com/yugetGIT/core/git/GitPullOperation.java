package com.yugetGIT.core.git;

import com.yugetGIT.util.ProgressParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class GitPullOperation {

    public static boolean pull(File repoDir, String remote, String branch, int timeoutSeconds, Consumer<String> onStep) {
        try {
            List<String> cmdArgs = new ArrayList<>();
            String gitExe = GitBootstrap.isGitResolved() ? com.yugetGIT.config.StateProperties.getGitPath() : "git";
            cmdArgs.add(gitExe);
            cmdArgs.add("pull");
            cmdArgs.add(remote);
            cmdArgs.add(branch);

            ProcessBuilder pb = new ProcessBuilder(cmdArgs);
            pb.directory(repoDir);
            pb.redirectErrorStream(true); // Merge stderr into stdout to parse progress

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
                        onStep.accept(pr.stage + " (" + pr.percentage + "%) " + pr.details);
                    }
                }
            }

            return process.waitFor() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}