package com.yugetGIT.core.git;

public class GitCredentialChecker {

    public static boolean hasUserName() {
        return checkConfig("user.name");
    }

    public static boolean hasUserEmail() {
        return checkConfig("user.email");
    }

    public static boolean hasCredentialHelper() {
        String helper = readConfigValue("credential.helper");
        return helper != null && !helper.isEmpty();
    }

    private static boolean checkConfig(String key) {
        String value = readConfigValue(key);
        return value != null && !value.isEmpty();
    }

    private static String readConfigValue(String key) {
        try {
            GitExecutor.GitResult result = GitExecutor.execute(null, 5, "config", "--global", key);
            if (!result.isSuccess() || result.stdout == null) {
                return null;
            }
            String value = result.stdout.trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
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