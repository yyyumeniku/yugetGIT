package com.yugetGIT.commands;

import com.yugetGIT.core.git.GitExecutor;
import com.yugetGIT.core.git.GitBootstrap;
import com.yugetGIT.core.git.RepoConfig;
import com.yugetGIT.util.BackgroundExecutor;
import com.yugetGIT.util.OsDetector;
import com.yugetGIT.util.PlatformPaths;
import com.yugetGIT.util.ProgressParser;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class YuCommand extends CommandBase {

    private static final int NETWORK_TIMEOUT_SECONDS = 300;

    @Override
    public String getName() {
        return "yu";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/yu <help|init|repo|list|fetch|push|pull|merge|reset>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "help", "init", "repo", "list", "fetch", "push", "pull", "merge", "reset");
        }

        if (args.length == 2 && "repo".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "add");
        }

        if (args.length == 2 && "push".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "--force");
        }

        if (args.length == 2 && "fetch".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "origin");
        }

        if (args.length == 2 && "reset".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "--hard");
        }

        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Unknown /yu usage. Run /yu help."));
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help":
                sendHelp(sender);
                return;
            case "init":
                if (args.length != 1) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu init usage. Run /yu help."));
                    return;
                }
                runInit(server, sender);
                return;
            case "repo":
                runRepo(server, sender, args);
                return;
            case "list":
                if (args.length != 1) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu list usage. Run /yu help."));
                    return;
                }
                runList(server, sender);
                return;
            case "fetch":
                if (args.length > 2) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu fetch usage. Run /yu help."));
                    return;
                }
                runNetworkOperation(server, sender, "fetch", false, args.length == 2 ? args[1] : "origin");
                return;
            case "push":
                if (args.length > 2 || (args.length == 2 && !"--force".equalsIgnoreCase(args[1]))) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu push usage. Run /yu help."));
                    return;
                }
                runNetworkOperation(server, sender, "push", args.length == 2, "origin");
                return;
            case "pull":
                if (args.length != 1) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu pull usage. Run /yu help."));
                    return;
                }
                runNetworkOperation(server, sender, "pull", false, "origin");
                return;
            case "merge":
                if (args.length != 1) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu merge usage. Run /yu help."));
                    return;
                }
                runNetworkOperation(server, sender, "merge", false, "origin");
                return;
            case "reset":
                runReset(server, sender, args);
                return;
            default:
                sender.sendMessage(formatMessage(TextFormatting.RED, "Unknown /yu subcommand: " + sub + ". Run /yu help."));
                return;
        }
    }

    private void sendHelp(ICommandSender sender) {
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/yu init"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/yu repo add <url>"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/yu list"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/yu fetch [remote]"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/yu push [--force]"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/yu pull"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/yu merge"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/yu reset --hard <ref>"));
    }

    private void runReset(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length != 3 || !"--hard".equalsIgnoreCase(args[1])) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu reset usage. Use /yu reset --hard <ref>."));
            return;
        }

        File repoDir = resolveWorldRepo(server);
        File gitDir = new File(repoDir, ".git");
        if (!gitDir.exists()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Run /yu init first."));
            return;
        }

        String targetRef = args[2].trim();
        if (targetRef.isEmpty()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Reset ref cannot be empty."));
            return;
        }

        try {
            GitExecutor.GitResult resetResult = GitExecutor.execute(repoDir, 120, "reset", "--hard", targetRef);
            if (resetResult.isSuccess()) {
                sender.sendMessage(formatMessage(TextFormatting.GREEN, "Reset complete: " + targetRef));
            } else {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Reset failed: " + shortGitError(resetResult)));
                if (targetRef.startsWith("origin/")) {
                    String activeBranch = resolveCurrentBranch(repoDir);
                    sender.sendMessage(formatMessage(TextFormatting.YELLOW, "Tip: try /yu reset --hard origin/" + activeBranch));
                }
            }
        } catch (Exception e) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Reset failed: " + e.getMessage()));
        }
    }

    private void runList(MinecraftServer server, ICommandSender sender) {
        File repoDir = resolveWorldRepo(server);
        File gitDir = new File(repoDir, ".git");
        if (!gitDir.exists()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Run /yu init first."));
            return;
        }

        sender.sendMessage(formatMessage(TextFormatting.GREEN, "Commits:"));
        try {
            GitExecutor.GitResult result = GitExecutor.execute(repoDir, 10, "log", "--format=%H\u001F%s");
            if (!result.isSuccess() || result.stdout == null || result.stdout.trim().isEmpty()) {
                sender.sendMessage(formatMessage(TextFormatting.GRAY, "No backups found."));
                return;
            }

            List<String> lines = new ArrayList<>(Arrays.asList(result.stdout.trim().split("\\n")));
            int total = lines.size();
            int showCount = Math.min(10, total);
            sender.sendMessage(formatMessage(TextFormatting.WHITE, "Showing " + showCount + " / " + total + " commits."));

            for (int displayIndex = 0; displayIndex < showCount; displayIndex++) {
                int restoreNumber = total - displayIndex;
                String logLine = lines.get(displayIndex).trim();
                if (logLine.isEmpty()) {
                    continue;
                }

                String[] parts = logLine.split("\\u001F", 2);
                if (parts.length == 2) {
                    String hash = parts[0].trim();
                    String shortHash = hash.length() > 7 ? hash.substring(0, 7) : hash;
                    String messagePreview = abbreviate(parts[1].trim(), 66);

                    TextComponentString lineComponent = new TextComponentString(
                        TextFormatting.DARK_GRAY + "#" + restoreNumber + " "
                            + TextFormatting.AQUA + shortHash + TextFormatting.DARK_GRAY + "  "
                            + TextFormatting.WHITE + messagePreview + " "
                    );

                    TextComponentString detailsButton = new TextComponentString(TextFormatting.AQUA + "[details]");
                    detailsButton.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/backup details -hash " + hash));
                    detailsButton.getStyle().setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new TextComponentString(TextFormatting.GRAY + "Show compact commit details")));
                    lineComponent.appendSibling(detailsButton);
                    sender.sendMessage(lineComponent);
                } else {
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "#" + restoreNumber + " " + logLine));
                }
            }
        } catch (Exception e) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to fetch backups."));
        }
    }

    private void runRepo(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length < 2 || !"add".equalsIgnoreCase(args[1])) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Unknown repo command. Run /yu help."));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Missing URL. Usage: /yu repo add <url>"));
            return;
        }

        File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
        String worldKey = worldDir.getName();
        File repoDir = PlatformPaths.getWorldsDir().resolve(worldKey).toFile();
        File gitDir = new File(repoDir, ".git");
        if (!gitDir.exists()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Run /yu init first before adding a remote."));
            return;
        }

        String remoteUrl = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        if (remoteUrl.isEmpty()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Remote URL cannot be empty."));
            return;
        }

        try {
            GitExecutor.GitResult hasOrigin = GitExecutor.execute(repoDir, 10, "remote", "get-url", "origin");
            GitExecutor.GitResult setResult;
            if (hasOrigin.isSuccess()) {
                setResult = GitExecutor.execute(repoDir, 10, "remote", "set-url", "origin", remoteUrl);
            } else {
                setResult = GitExecutor.execute(repoDir, 10, "remote", "add", "origin", remoteUrl);
            }

            if (!setResult.isSuccess()) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to set origin remote."));
                sender.sendMessage(formatMessage(TextFormatting.RED, shortGitError(setResult)));
                return;
            }

            sender.sendMessage(formatMessage(TextFormatting.GREEN, "Remote origin configured: " + remoteUrl));
        } catch (Exception e) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to configure remote: " + e.getMessage()));
        }
    }

    private void runNetworkOperation(MinecraftServer server, ICommandSender sender, String operation, boolean forcePush, String remoteName) {
        File repoDir = resolveWorldRepo(server);
        File gitDir = new File(repoDir, ".git");
        if (!gitDir.exists()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Run /yu init first."));
            return;
        }

        String originUrl = getRemoteUrl(repoDir, remoteName);
        if (originUrl == null || originUrl.isEmpty()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Remote '" + remoteName + "' not found. Run /yu repo add <url>."));
            return;
        }

        sender.sendMessage(formatMessage(TextFormatting.WHITE, "Starting git " + operation + "..."));
        BossInfoServer networkBar = createNetworkBossBar(sender, operation);
        bumpNetworkBossBar(server, networkBar, operation, 15, null);
        BackgroundExecutor.execute(() -> {
            try {
                String branch = resolveCurrentBranch(repoDir);

                GitExecutor.GitResult result = runGitWithProgress(repoDir, operation, branch, forcePush, remoteName,
                    progress -> updateNetworkBossBar(server, networkBar, operation, progress));

                clearNetworkBossBar(server, sender, networkBar);

                if (result.isSuccess()) {
                    if ("fetch".equals(operation)) {
                        handleFetchResult(server, sender, repoDir, branch, remoteName);
                    } else {
                        sendScheduled(server, sender, formatMessage(TextFormatting.GREEN, "git " + operation + " succeeded."));
                    }
                    return;
                }

                sendScheduled(server, sender, formatMessage(TextFormatting.RED, "git " + operation + " failed."));
                String errorText = shortGitError(result);
                String loweredError = errorText.toLowerCase();
                if ("push".equals(operation) && loweredError.contains("non-fast-forward")) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.YELLOW, "Push was rejected because remote has newer commits."));
                    sendScheduled(server, sender, formatMessage(TextFormatting.WHITE, "Run /yu pull (or /yu merge), resolve conflicts if needed, then /yu push again."));
                    sendScheduled(server, sender, formatMessage(TextFormatting.WHITE, "Use /yu push --force only if you want to overwrite remote history."));
                    return;
                }

                if (("pull".equals(operation) || "merge".equals(operation)) && loweredError.contains("unstaged changes")) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.YELLOW, "Pull blocked by local unstaged changes."));
                    sendScheduled(server, sender, formatMessage(TextFormatting.WHITE, "Create a backup or reset local state, then retry pull/merge."));
                    return;
                }

                if ("merge".equals(operation) && (loweredError.contains("automatic merge failed") || loweredError.contains("conflict"))) {
                    try {
                        GitExecutor.execute(repoDir, 10, "merge", "--abort");
                    } catch (Exception ignored) {
                    }
                    sendScheduled(server, sender, formatMessage(TextFormatting.YELLOW, "Merge hit binary conflicts and was aborted automatically."));
                    sendScheduled(server, sender, formatMessage(TextFormatting.WHITE, "Use /yu pull (rebase) or /yu push --force depending on which history you want to keep."));
                    return;
                }

                sendScheduled(server, sender, formatMessage(TextFormatting.RED, errorText));
                if ("push".equals(operation) && errorText.toLowerCase().contains("src refspec") && errorText.toLowerCase().contains("does not match any")) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.YELLOW, "No local commit exists yet. Run /backup save first."));
                }
            } catch (Exception e) {
                clearNetworkBossBar(server, sender, networkBar);
                sendScheduled(server, sender, formatMessage(TextFormatting.RED, "git " + operation + " failed: " + e.getMessage()));
            }
        });
    }

    private GitExecutor.GitResult runGitWithProgress(File repoDir, String operation, String branch, boolean forcePush, String remoteName,
                                                     java.util.function.Consumer<ProgressParser.ParseResult> onProgress) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        String gitExe = GitBootstrap.isGitResolved() ? com.yugetGIT.config.StateProperties.getGitPath() : "git";
        cmdArgs.add(gitExe);

        if ("fetch".equals(operation)) {
            cmdArgs.addAll(Arrays.asList("fetch", remoteName, "--prune"));
        } else if ("push".equals(operation)) {
            if (forcePush) {
                cmdArgs.addAll(Arrays.asList("push", "--force", "-u", remoteName, branch));
            } else {
                cmdArgs.addAll(Arrays.asList("push", "-u", remoteName, branch));
            }
        } else if ("merge".equals(operation)) {
            cmdArgs.addAll(Arrays.asList("pull", "--no-rebase", remoteName, branch));
        } else {
            cmdArgs.addAll(Arrays.asList("pull", "--rebase", remoteName, branch));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(cmdArgs);
        processBuilder.directory(repoDir);
        processBuilder.redirectErrorStream(true);

        Map<String, String> env = processBuilder.environment();
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("HOME", System.getProperty("user.home"));

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

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                ProgressParser.ParseResult parsed = ProgressParser.parseLine(line);
                if (parsed != null) {
                    onProgress.accept(parsed);
                }
            }
        }

        int exitCode = process.waitFor();
        return new GitExecutor.GitResult(exitCode, output.toString(), output.toString());
    }

    private void handleFetchResult(MinecraftServer server, ICommandSender sender, File repoDir, String branch, String remoteName) {
        try {
            GitExecutor.GitResult logResult = GitExecutor.execute(repoDir, 10, "log", "--pretty=format:%h %s", "-5", "HEAD..refs/remotes/" + remoteName + "/" + branch);
            if (!logResult.isSuccess() || logResult.stdout == null || logResult.stdout.trim().isEmpty()) {
                sendScheduled(server, sender, formatMessage(TextFormatting.GREEN, "No changes fetched"));
                return;
            }

            String[] lines = logResult.stdout.trim().split("\\n");
            for (String line : lines) {
                String row = line == null ? "" : line.trim();
                if (row.isEmpty()) {
                    continue;
                }
                sendScheduled(server, sender, new TextComponentString(TextFormatting.GRAY + row));
            }
        } catch (Exception e) {
            sendScheduled(server, sender, formatMessage(TextFormatting.GREEN, "No changes fetched"));
        }
    }

    private void runInit(MinecraftServer server, ICommandSender sender) {
        File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
        String worldKey = worldDir.getName();
        File repoDir = PlatformPaths.getWorldsDir().resolve(worldKey).toFile();

        try {
            if (!repoDir.exists() && !repoDir.mkdirs()) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to create world backup directory."));
                return;
            }

            File gitDir = new File(repoDir, ".git");
            if (!gitDir.exists()) {
                RepoConfig.initAndConfigure(repoDir);
                sender.sendMessage(formatMessage(TextFormatting.GREEN, "Initialized local repository for this world."));
            } else {
                RepoConfig.ensureOperationalConfig(repoDir);
                sender.sendMessage(formatMessage(TextFormatting.WHITE, "Repository already initialized. Refreshing operational config."));
            }

            String worldBranch = buildWorldBranch(worldKey);
            if (!checkoutOrCreateBranch(repoDir, worldBranch)) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to switch/create world branch: " + worldBranch));
                return;
            }

            sender.sendMessage(formatMessage(TextFormatting.GREEN, "yu init complete on branch " + worldBranch));
            sender.sendMessage(formatMessage(TextFormatting.WHITE, "Next: run /backup save -m \"first backup\""));
            sender.sendMessage(formatMessage(TextFormatting.WHITE, "Optional remote: /yu repo add <url>"));
            sender.sendMessage(formatMessage(TextFormatting.WHITE, "Then sync with /yu push or /yu pull"));
        } catch (Exception e) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "yu init failed: " + e.getMessage()));
        }
    }

    private boolean checkoutOrCreateBranch(File repoDir, String branchName) {
        try {
            if (com.yugetGIT.core.git.GitExecutor.execute(repoDir, 15, "checkout", branchName).isSuccess()) {
                return true;
            }

            if (com.yugetGIT.core.git.GitExecutor.execute(repoDir, 15, "checkout", "-b", branchName).isSuccess()) {
                return true;
            }

            return com.yugetGIT.core.git.GitExecutor.execute(repoDir, 15, "symbolic-ref", "HEAD", "refs/heads/" + branchName).isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    private String buildWorldBranch(String worldKey) {
        String normalized = worldKey == null ? "world" : worldKey.trim().toLowerCase();
        normalized = normalized.replaceAll("\\s+", "-");
        normalized = normalized.replaceAll("[^a-z0-9._/-]", "-");
        if (normalized.isEmpty()) {
            normalized = "world";
        }
        return "world/" + normalized;
    }

    private TextComponentString formatMessage(TextFormatting color, String text) {
        return new TextComponentString(TextFormatting.GOLD + "[yugetGIT] " + color + text);
    }

    private String shortGitError(GitExecutor.GitResult result) {
        if (result == null) {
            return "Unknown git error.";
        }
        if (result.stderr != null && !result.stderr.trim().isEmpty()) {
            return result.stderr.trim();
        }
        if (result.stdout != null && !result.stdout.trim().isEmpty()) {
            return result.stdout.trim();
        }
        return "Unknown git error.";
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        String normalized = text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private File resolveWorldRepo(MinecraftServer server) {
        File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
        String worldKey = worldDir.getName();
        return PlatformPaths.getWorldsDir().resolve(worldKey).toFile();
    }

    private String getRemoteUrl(File repoDir, String remoteName) {
        try {
            GitExecutor.GitResult result = GitExecutor.execute(repoDir, 10, "remote", "get-url", remoteName);
            if (result.isSuccess() && result.stdout != null && !result.stdout.trim().isEmpty()) {
                return result.stdout.trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String resolveCurrentBranch(File repoDir) {
        try {
            GitExecutor.GitResult branchResult = GitExecutor.execute(repoDir, 10, "rev-parse", "--abbrev-ref", "HEAD");
            if (branchResult.isSuccess() && branchResult.stdout != null) {
                String branch = branchResult.stdout.trim();
                if (!branch.isEmpty() && !"HEAD".equals(branch)) {
                    return branch;
                }
            }
        } catch (Exception ignored) {
        }
        return "main";
    }

    private void sendScheduled(MinecraftServer server, ICommandSender sender, TextComponentString message) {
        try {
            server.addScheduledTask(() -> sender.sendMessage(message));
        } catch (Exception ignored) {
            sender.sendMessage(message);
        }
    }

    private BossInfoServer createNetworkBossBar(ICommandSender sender, String operation) {
        if (!(sender instanceof EntityPlayerMP)) {
            return null;
        }

        String title;
        if ("push".equals(operation)) {
            title = "Pushing commits...";
        } else if ("pull".equals(operation)) {
            title = "Pulling commits...";
        } else if ("merge".equals(operation)) {
            title = "Merging commits...";
        } else {
            title = "Fetching commits...";
        }

        BossInfoServer bar = new BossInfoServer(
            new TextComponentString(TextFormatting.GOLD + title),
            BossInfo.Color.YELLOW,
            BossInfo.Overlay.NOTCHED_20
        );
        bar.setPercent(0.05f);
        bar.addPlayer((EntityPlayerMP) sender);
        return bar;
    }

    private void updateNetworkBossBar(MinecraftServer server, BossInfoServer bar, String operation, ProgressParser.ParseResult progress) {
        if (bar == null || progress == null) {
            return;
        }

        server.addScheduledTask(() -> {
            float normalized = Math.max(0.0f, Math.min(1.0f, progress.percentage / 100.0f));
            bar.setPercent(normalized);
            String prefix;
            if ("fetch".equals(operation)) {
                prefix = "Fetching commits";
            } else if ("push".equals(operation)) {
                prefix = "Pushing commits";
            } else if ("merge".equals(operation)) {
                prefix = "Merging commits";
            } else {
                prefix = "Pulling commits";
            }
            String details = progress.details == null || progress.details.trim().isEmpty() ? "" : (" | " + progress.details.trim());
            bar.setName(new TextComponentString(TextFormatting.GOLD + prefix + "..." + details));
        });
    }

    private void bumpNetworkBossBar(MinecraftServer server, BossInfoServer bar, String operation, int percent, String details) {
        if (bar == null) {
            return;
        }

        server.addScheduledTask(() -> {
            int bounded = Math.max(0, Math.min(100, percent));
            bar.setPercent(bounded / 100.0f);
            String prefix;
            if ("fetch".equals(operation)) {
                prefix = "Fetching commits";
            } else if ("push".equals(operation)) {
                prefix = "Pushing commits";
            } else if ("merge".equals(operation)) {
                prefix = "Merging commits";
            } else {
                prefix = "Pulling commits";
            }
            String suffix = (details == null || details.trim().isEmpty()) ? "" : (" | " + details.trim());
            bar.setName(new TextComponentString(TextFormatting.GOLD + prefix + "..." + suffix));
        });
    }

    private void clearNetworkBossBar(MinecraftServer server, ICommandSender sender, BossInfoServer bar) {
        if (bar == null || !(sender instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        server.addScheduledTask(() -> {
            try {
                if (player.connection != null) {
                    bar.setPercent(1.0f);
                }
            } catch (Exception ignored) {
            }
        });

        BackgroundExecutor.execute(() -> {
            try {
                Thread.sleep(400L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            server.addScheduledTask(() -> {
                try {
                    if (player.connection != null) {
                        bar.removePlayer(player);
                    }
                } catch (Exception ignored) {
                }
            });
        });
    }
}
