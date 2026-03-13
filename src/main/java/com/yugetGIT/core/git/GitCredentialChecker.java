package com.yugetGIT.core.git;

public class GitCredentialChecker {

    public static boolean hasUserName() {
        return checkConfig("user.name");
    }

    public static boolean hasUserEmail() {
        return checkConfig("user.email");
    }

    private static boolean checkConfig(String key) {
        try {
            GitExecutor.GitResult result = GitExecutor.execute(null, 5, "config", "--global", key);
            return result.isSuccess() && !result.stdout.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canAuthenticate(String remoteUrl) {
        try {
            // Uses ls-remote to check auth without pushing/pulling
            GitExecutor.GitResult result = GitExecutor.execute(null, 15, "ls-remote", remoteUrl, "HEAD");
            return result.isSuccess();
        } catch (Exception e) {
            return false;
        }
    }
}