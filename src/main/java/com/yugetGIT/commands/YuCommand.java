package com.yugetGIT.commands;

import com.yugetGIT.core.git.GitExecutor;
import com.yugetGIT.core.git.GitBootstrap;
import com.yugetGIT.core.git.RepoConfig;
import com.yugetGIT.config.yugetGITConfig;
import com.yugetGIT.yugetgit.Tags;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class YuCommand extends CommandBase {

    private static final int DEFAULT_NETWORK_TIMEOUT_SECONDS = 60;
    private static final String MAIN_BRANCH = "main";
    private static final String METADATA_DIR = ".yugetgit";
    private static final String MOD_ICON_FILE = "mod-icon.png";
    private static final String BRANCH_INDEX_FILE = "branch-index.md";
    private static final String README_FILE = "README.md";

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

        String normalizedRemoteUrl = normalizeRemoteUrl(remoteUrl);
        if (!normalizedRemoteUrl.equals(remoteUrl)) {
            sender.sendMessage(formatMessage(TextFormatting.YELLOW,
                "Normalized remote URL to repository root: " + normalizedRemoteUrl));
        }

        try {
            GitExecutor.GitResult hasOrigin = GitExecutor.execute(repoDir, 10, "remote", "get-url", "origin");
            GitExecutor.GitResult setResult;
            if (hasOrigin.isSuccess()) {
                setResult = GitExecutor.execute(repoDir, 10, "remote", "set-url", "origin", normalizedRemoteUrl);
            } else {
                setResult = GitExecutor.execute(repoDir, 10, "remote", "add", "origin", normalizedRemoteUrl);
            }

            if (!setResult.isSuccess()) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to set origin remote."));
                sender.sendMessage(formatMessage(TextFormatting.RED, shortGitError(setResult)));
                return;
            }

            sender.sendMessage(formatMessage(TextFormatting.GREEN, "Remote origin configured: " + normalizedRemoteUrl));

            String worldBranch = buildWorldBranch(worldKey);
            if (refreshMainMetadataAfterRemoteAdd(repoDir, worldBranch, sender)) {
                sender.sendMessage(formatMessage(TextFormatting.GREEN, "Updated main README metadata for this remote."));
            } else {
                sender.sendMessage(formatMessage(TextFormatting.YELLOW,
                    "Remote set, but failed to refresh main README metadata automatically."));
            }

            if (checkoutOrCreateBranch(repoDir, worldBranch)) {
                sender.sendMessage(formatMessage(TextFormatting.GREEN,
                    "Switched to world branch: " + worldBranch + ". Run /yu push to sync world commits."));
            } else {
                sender.sendMessage(formatMessage(TextFormatting.YELLOW,
                    "Remote configured, but failed to switch to world branch: " + worldBranch));
            }
        } catch (Exception e) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to configure remote: " + e.getMessage()));
        }
    }

    private boolean refreshMainMetadataAfterRemoteAdd(File repoDir, String worldBranch, ICommandSender sender) {
        try {
            if (!checkoutOrCreateBranch(repoDir, MAIN_BRANCH)) {
                return false;
            }

            writeRepositoryMetadata(repoDir, worldBranch);
            ensureLocalCommitIdentity(repoDir);
            commitRepositoryMetadataIfNeeded(repoDir, sender);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String normalizeRemoteUrl(String remoteUrl) {
        if (remoteUrl == null) {
            return "";
        }

        String trimmed = remoteUrl.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        String lowered = trimmed.toLowerCase();
        if (!lowered.startsWith("https://github.com/") && !lowered.startsWith("http://github.com/")) {
            return trimmed;
        }

        int queryIndex = trimmed.indexOf('?');
        if (queryIndex >= 0) {
            trimmed = trimmed.substring(0, queryIndex);
        }
        int fragmentIndex = trimmed.indexOf('#');
        if (fragmentIndex >= 0) {
            trimmed = trimmed.substring(0, fragmentIndex);
        }

        String prefix = trimmed.startsWith("http://") ? "http://github.com/" : "https://github.com/";
        String path = trimmed.substring(prefix.length());
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String[] parts = path.split("/");
        if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return remoteUrl;
        }

        return prefix + parts[0].trim() + "/" + parts[1].trim();
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
        int networkTimeoutSeconds = resolveNetworkTimeoutSeconds();
        sender.sendMessage(formatMessage(TextFormatting.GRAY,
            "If the remote does not respond, this command times out after " + networkTimeoutSeconds + " seconds."));
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

    private boolean ensureRemoteMainBranch(File repoDir, String remoteName) {
        try {
            if (!hasBranch(repoDir, MAIN_BRANCH)) {
                return false;
            }

            GitExecutor.GitResult hasRemoteMain = GitExecutor.execute(repoDir, 20,
                "ls-remote", "--exit-code", "--heads", remoteName, MAIN_BRANCH);
            if (hasRemoteMain.isSuccess()) {
                return true;
            }

            GitExecutor.GitResult pushMain = GitExecutor.execute(repoDir, resolveNetworkTimeoutSeconds(),
                "push", "-u", remoteName, MAIN_BRANCH);
            return pushMain.isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    private GitExecutor.GitResult runGitWithProgress(File repoDir, String operation, String branch, boolean forcePush, String remoteName,
                                                     java.util.function.Consumer<ProgressParser.ParseResult> onProgress) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        String gitExe = GitBootstrap.isGitResolved() ? com.yugetGIT.config.StateProperties.getGitPath() : "git";
        cmdArgs.add(gitExe);
        if (isInsecureTlsAllowed()) {
            cmdArgs.addAll(Arrays.asList("-c", "http.sslVerify=false"));
        }

        if ("fetch".equals(operation)) {
            cmdArgs.addAll(Arrays.asList("fetch", remoteName, "--prune"));
        } else if ("push".equals(operation)) {
            if (forcePush) {
                cmdArgs.addAll(Arrays.asList("push", "--force", "--all", remoteName));
            } else {
                cmdArgs.addAll(Arrays.asList("push", "--all", remoteName));
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
        env.put("GCM_INTERACTIVE", "Never");
        env.put("GIT_ASKPASS", "echo");
        env.put("SSH_ASKPASS", "echo");
        if (isInsecureTlsAllowed()) {
            env.put("GIT_SSL_NO_VERIFY", "1");
        }

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
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                    ProgressParser.ParseResult parsed = ProgressParser.parseLine(line);
                    if (parsed != null) {
                        onProgress.accept(parsed);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "yugetgit-git-progress-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        int networkTimeoutSeconds = resolveNetworkTimeoutSeconds();
        boolean finished = process.waitFor(networkTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            try {
                outputReader.join(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String currentOutput;
            synchronized (output) {
                currentOutput = output.toString();
            }
            String timeoutMessage = "git " + operation + " timed out after " + networkTimeoutSeconds + " seconds.";
            return new GitExecutor.GitResult(124, currentOutput, timeoutMessage);
        }

        try {
            outputReader.join(500L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        int exitCode = process.exitValue();
        String finalOutput;
        synchronized (output) {
            finalOutput = output.toString();
        }
        return new GitExecutor.GitResult(exitCode, finalOutput, finalOutput);
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
            boolean worldBranchAlreadyExists = hasBranch(repoDir, worldBranch);

            String headBeforeInit = resolveHeadCommit(repoDir);
            if (!ensureWorldBranchReference(repoDir, worldBranch, headBeforeInit)) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to create/update world branch: " + worldBranch));
                return;
            }

            if (!rebuildMainMetadataBranch(repoDir, worldBranch, sender)) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to rebuild main metadata branch."));
                return;
            }

            if (!hasBranch(repoDir, worldBranch) && !createBranchIfMissing(repoDir, worldBranch)) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to create world branch: " + worldBranch));
                return;
            }

            if (!worldBranchAlreadyExists) {
                initializeWorldBranchWithoutMetadata(repoDir, worldBranch);
            }

            if (!checkoutOrCreateBranch(repoDir, MAIN_BRANCH)) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to switch/create main branch."));
                return;
            }

            sender.sendMessage(formatMessage(TextFormatting.GREEN, "yu init complete. Default branch is " + MAIN_BRANCH + ", world branch is " + worldBranch));
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

    private boolean createBranchIfMissing(File repoDir, String branchName) {
        try {
            if (hasBranch(repoDir, branchName)) {
                return true;
            }

            if (GitExecutor.execute(repoDir, 10, "branch", branchName).isSuccess()) {
                return true;
            }

            if (GitExecutor.execute(repoDir, 10, "checkout", "-b", branchName).isSuccess()) {
                GitExecutor.execute(repoDir, 10, "checkout", MAIN_BRANCH);
                return true;
            }

            return hasBranch(repoDir, branchName);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasBranch(File repoDir, String branchName) {
        try {
            return GitExecutor.execute(repoDir, 10, "show-ref", "--verify", "--quiet", "refs/heads/" + branchName).isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveHeadCommit(File repoDir) {
        try {
            GitExecutor.GitResult headResult = GitExecutor.execute(repoDir, 10, "rev-parse", "--verify", "HEAD");
            if (headResult.isSuccess() && headResult.stdout != null && !headResult.stdout.trim().isEmpty()) {
                return headResult.stdout.trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean ensureWorldBranchReference(File repoDir, String worldBranch, String sourceCommit) {
        try {
            if (hasBranch(repoDir, worldBranch)) {
                return true;
            }

            if (sourceCommit != null && !sourceCommit.trim().isEmpty()) {
                return GitExecutor.execute(repoDir, 10, "branch", worldBranch, sourceCommit).isSuccess();
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean rebuildMainMetadataBranch(File repoDir, String worldBranch, ICommandSender sender) {
        try {
            if (!GitExecutor.execute(repoDir, 15, "checkout", "--orphan", MAIN_BRANCH).isSuccess()) {
                if (!checkoutOrCreateBranch(repoDir, MAIN_BRANCH)) {
                    return false;
                }
            }

            try {
                GitExecutor.execute(repoDir, 10, "rm", "-rf", "--cached", ".");
            } catch (Exception ignored) {
            }
            try {
                GitExecutor.execute(repoDir, 10, "clean", "-fdx");
            } catch (Exception ignored) {
            }

            writeRepositoryMetadata(repoDir, worldBranch);
            ensureLocalCommitIdentity(repoDir);
            commitRepositoryMetadataIfNeeded(repoDir, sender);

            return hasBranch(repoDir, MAIN_BRANCH) || checkoutOrCreateBranch(repoDir, MAIN_BRANCH);
        } catch (Exception e) {
            return false;
        }
    }

    private void initializeWorldBranchWithoutMetadata(File repoDir, String worldBranch) {
        try {
            if (!checkoutOrCreateBranch(repoDir, worldBranch)) {
                return;
            }

            try {
                GitExecutor.execute(repoDir, 10, "rm", "-rf", "--ignore-unmatch", README_FILE, METADATA_DIR);
            } catch (Exception ignored) {
            }

            File readmePath = new File(repoDir, README_FILE);
            if (readmePath.exists()) {
                try {
                    Files.delete(readmePath.toPath());
                } catch (Exception ignored) {
                }
            }

            File metadataPath = new File(repoDir, METADATA_DIR);
            if (metadataPath.exists()) {
                try {
                    GitExecutor.execute(repoDir, 10, "clean", "-fdx", METADATA_DIR);
                } catch (Exception ignored) {
                }
            }

            GitExecutor.GitResult statusResult = GitExecutor.execute(repoDir, 10, "status", "--porcelain");
            if (statusResult.isSuccess() && statusResult.stdout != null && !statusResult.stdout.trim().isEmpty()) {
                ensureLocalCommitIdentity(repoDir);
                GitExecutor.execute(repoDir, 10, "add", "-A");
                GitExecutor.execute(repoDir, 10, "commit", "-m", "Initialize world branch");
            }
        } catch (Exception ignored) {
        } finally {
            checkoutOrCreateBranch(repoDir, MAIN_BRANCH);
        }
    }

    private void writeRepositoryMetadata(File repoDir, String worldBranch) {
        try {
            File metadataDirectory = new File(repoDir, METADATA_DIR);
            if (!metadataDirectory.exists() && !metadataDirectory.mkdirs()) {
                return;
            }

            writeModIcon(metadataDirectory);

            File branchIndexPath = new File(metadataDirectory, BRANCH_INDEX_FILE);
            Set<String> indexedBranches = loadIndexedBranches(branchIndexPath);
            indexedBranches.add(MAIN_BRANCH);
            indexedBranches.add(worldBranch);
            indexedBranches.addAll(listLocalBranches(repoDir));
            saveBranchIndex(branchIndexPath, indexedBranches);

            File readmePath = new File(repoDir, README_FILE);
            saveRepositoryReadme(readmePath, indexedBranches, getRemoteUrl(repoDir, "origin"));
        } catch (Exception ignored) {
        }
    }

    private Set<String> listLocalBranches(File repoDir) {
        Set<String> localBranches = new LinkedHashSet<>();
        try {
            GitExecutor.GitResult branchResult = GitExecutor.execute(repoDir, 10, "for-each-ref", "--format=%(refname:short)", "refs/heads");
            if (!branchResult.isSuccess() || branchResult.stdout == null || branchResult.stdout.trim().isEmpty()) {
                return localBranches;
            }

            String[] lines = branchResult.stdout.split("\\n");
            for (String line : lines) {
                if (line == null) {
                    continue;
                }

                String branch = line.trim();
                if (!branch.isEmpty()) {
                    localBranches.add(branch);
                }
            }
        } catch (Exception ignored) {
        }
        return localBranches;
    }

    private void writeModIcon(File metadataDirectory) {
        File iconPath = new File(metadataDirectory, MOD_ICON_FILE);
        if (iconPath.exists() && iconPath.length() > 0) {
            return;
        }

        try (InputStream iconStream = YuCommand.class.getResourceAsStream("/assets/yugetgit/logo.png")) {
            if (iconStream == null) {
                return;
            }

            try (FileOutputStream outputStream = new FileOutputStream(iconPath)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = iconStream.read(buffer)) >= 0) {
                    outputStream.write(buffer, 0, read);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Set<String> loadIndexedBranches(File branchIndexPath) {
        Set<String> indexedBranches = new LinkedHashSet<>();
        if (!branchIndexPath.exists()) {
            return indexedBranches;
        }

        try {
            List<String> lines = Files.readAllLines(branchIndexPath.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("- ")) {
                    String branchName = trimmed.substring(2).trim();
                    if (!branchName.isEmpty()) {
                        indexedBranches.add(branchName);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return indexedBranches;
    }

    private void saveBranchIndex(File branchIndexPath, Set<String> indexedBranches) {
        List<String> lines = new ArrayList<>();
        lines.add("# Branch Index");
        lines.add("");
        lines.add("Branches created by yugetGIT init:");
        lines.add("");
        for (String branch : indexedBranches) {
            lines.add("- " + branch);
        }

        try {
            Files.write(branchIndexPath.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private void saveRepositoryReadme(File readmePath, Set<String> indexedBranches, String remoteUrl) {
        List<String> lines = new ArrayList<>();
        lines.add("# yugetGIT World Repository");
        lines.add("");
        lines.add("![yugetGIT icon](./" + METADATA_DIR + "/" + MOD_ICON_FILE + ")");
        lines.add("");
        lines.add("- Mod: **" + Tags.MOD_NAME + "**");
        lines.add("- Version: **" + Tags.VERSION + "**");
        lines.add("");
        lines.add("## Branch Index");
        lines.add("");
        lines.add("| # | Branch |");
        lines.add("|---|--------|");

        int index = 1;
        for (String branch : indexedBranches) {
            String branchLink = resolveReadmeBranchLink(branch, remoteUrl);
            lines.add("| " + index + " | [" + branch + "](" + branchLink + ") |");
            index++;
        }

        lines.add("");
        lines.add("This file is generated by `/yu init` and refreshed when new world branches are initialized.");

        try {
            Files.write(readmePath.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private String resolveReadmeBranchLink(String branch, String remoteUrl) {
        String encodedBranch = branch.replace("/", "%2F");
        String webRepoBaseUrl = resolveWebRepoBaseUrl(remoteUrl);
        if (webRepoBaseUrl != null) {
            if (webRepoBaseUrl.contains("github.com/")) {
                return webRepoBaseUrl + "/tree/" + encodedBranch;
            }
            return webRepoBaseUrl + "/src/branch/" + encodedBranch;
        }
        return "../../tree/" + encodedBranch;
    }

    private String resolveWebRepoBaseUrl(String remoteUrl) {
        if (remoteUrl == null) {
            return null;
        }

        String trimmed = remoteUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String schemeAndHost;
        String repoPath;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            int schemeEnd = trimmed.indexOf("://") + 3;
            int hostEnd = trimmed.indexOf('/', schemeEnd);
            if (hostEnd < 0) {
                return null;
            }
            schemeAndHost = trimmed.substring(0, hostEnd);
            repoPath = trimmed.substring(hostEnd + 1);
        } else if (trimmed.startsWith("git@") && trimmed.contains(":")) {
            int atIndex = trimmed.indexOf('@');
            int colonIndex = trimmed.indexOf(':', atIndex + 1);
            if (colonIndex < 0) {
                return null;
            }
            String host = trimmed.substring(atIndex + 1, colonIndex);
            schemeAndHost = "https://" + host;
            repoPath = trimmed.substring(colonIndex + 1);
        } else if (trimmed.startsWith("ssh://git@")) {
            String noScheme = trimmed.substring("ssh://git@".length());
            int slashIndex = noScheme.indexOf('/');
            if (slashIndex < 0) {
                return null;
            }
            String host = noScheme.substring(0, slashIndex);
            schemeAndHost = "https://" + host;
            repoPath = noScheme.substring(slashIndex + 1);
        } else {
            return null;
        }

        int queryIndex = repoPath.indexOf('?');
        if (queryIndex >= 0) {
            repoPath = repoPath.substring(0, queryIndex);
        }
        int fragmentIndex = repoPath.indexOf('#');
        if (fragmentIndex >= 0) {
            repoPath = repoPath.substring(0, fragmentIndex);
        }

        if (repoPath.endsWith(".git")) {
            repoPath = repoPath.substring(0, repoPath.length() - 4);
        }
        while (repoPath.endsWith("/")) {
            repoPath = repoPath.substring(0, repoPath.length() - 1);
        }

        String[] parts = repoPath.split("/");
        if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return null;
        }

        return schemeAndHost + "/" + parts[0].trim() + "/" + parts[1].trim();
    }

    private void commitRepositoryMetadataIfNeeded(File repoDir, ICommandSender sender) {
        try {
            GitExecutor.GitResult statusResult = GitExecutor.execute(repoDir, 10, "status", "--porcelain");
            if (!statusResult.isSuccess() || statusResult.stdout == null || statusResult.stdout.trim().isEmpty()) {
                return;
            }

            GitExecutor.execute(repoDir, 10, "add", README_FILE, METADATA_DIR);
            GitExecutor.GitResult commitResult = GitExecutor.execute(repoDir, 10, "commit", "-m", "Repository created via /yu init");
            if (!commitResult.isSuccess()) {
                sender.sendMessage(formatMessage(TextFormatting.YELLOW,
                    "Metadata files were created, but auto-commit failed. Configure git user.name/email and commit manually."));
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureLocalCommitIdentity(File repoDir) {
        try {
            GitExecutor.GitResult nameResult = GitExecutor.execute(repoDir, 5, "config", "--get", "user.name");
            if (!nameResult.isSuccess() || nameResult.stdout == null || nameResult.stdout.trim().isEmpty()) {
                GitExecutor.execute(repoDir, 5, "config", "user.name", "yugetGIT");
            }

            GitExecutor.GitResult emailResult = GitExecutor.execute(repoDir, 5, "config", "--get", "user.email");
            if (!emailResult.isSuccess() || emailResult.stdout == null || emailResult.stdout.trim().isEmpty()) {
                GitExecutor.execute(repoDir, 5, "config", "user.email", "yugetgit@local.invalid");
            }
        } catch (Exception ignored) {
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

    private int resolveNetworkTimeoutSeconds() {
        int configured = DEFAULT_NETWORK_TIMEOUT_SECONDS;
        try {
            configured = yugetGITConfig.gitNetwork.yuCommandTimeoutSeconds;
        } catch (Exception ignored) {
            configured = DEFAULT_NETWORK_TIMEOUT_SECONDS;
        }
        if (configured < 5) {
            return 5;
        }
        return configured;
    }

    private boolean isInsecureTlsAllowed() {
        return yugetGITConfig.gitNetwork.allowInsecureTls;
    }
}
