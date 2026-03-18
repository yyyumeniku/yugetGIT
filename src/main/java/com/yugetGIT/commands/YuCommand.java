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
import com.yugetGIT.util.VisualDiffSessionManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class YuCommand extends CommandBase {

    private static final int DEFAULT_NETWORK_TIMEOUT_SECONDS = 60;
    private static final String MAIN_BRANCH = "main";
    private static final String METADATA_DIR = ".yugetgit";
    private static final String MOD_ICON_FILE = "mod-icon.png";
    private static final String README_FILE = "README.md";
    private final BackupCommand backupCommand = new BackupCommand();
    private volatile boolean networkTimeoutHintShown = false;

    private static final class ParsedArgs {
        private String subcommand;
        private final List<String> positionals = new ArrayList<>();
        private boolean force;
        private boolean hard;
        private boolean valid = true;
        private String error = "";

        static ParsedArgs parse(String[] args) {
            ParsedArgs parsed = new ParsedArgs();
            if (args == null || args.length == 0) {
                parsed.valid = false;
                parsed.error = "Unknown /yu usage. Run /yu help.";
                return parsed;
            }

            parsed.subcommand = args[0].toLowerCase();
            for (int i = 1; i < args.length; i++) {
                String token = args[i];
                String lowered = token.toLowerCase();
                if ("--force".equals(lowered)) {
                    parsed.force = true;
                    continue;
                }
                if ("--hard".equals(lowered)) {
                    parsed.hard = true;
                    continue;
                }
                if (token.startsWith("-")) {
                    parsed.valid = false;
                    parsed.error = "Unknown flag: " + token;
                    return parsed;
                }
                parsed.positionals.add(token);
            }

            return parsed;
        }
    }

    @Override
    public String getName() {
        return "yu";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/yu <help|init|remote|backup|diff|debug-dialog|fetch|push|pull|merge|reset>";
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
            return getListOfStringsMatchingLastWord(args, "help", "init", "remote", "backup", "diff", "debug-dialog", "fetch", "push", "pull", "merge", "reset");
        }

        if (args.length == 2 && "backup".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "help", "save", "list", "details", "restore", "worlds", "status");
        }

        if (args.length == 2 && "remote".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "add");
        }

        if (args.length == 2 && "push".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "--force");
        }

        if (args.length == 2 && "pull".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "--hard");
        }

        if (args.length == 2 && "fetch".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "origin");
        }

        if (args.length == 2 && "diff".equalsIgnoreCase(args[0])) {
            return Collections.emptyList();
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

        String rawSub = args[0].toLowerCase();
        if ("backup".equals(rawSub)) {
            runBackupSubcommand(server, sender, args);
            return;
        }

        ParsedArgs parsed = ParsedArgs.parse(args);
        if (!parsed.valid) {
            sender.sendMessage(formatMessage(TextFormatting.RED, parsed.error));
            return;
        }

        String sub = parsed.subcommand;
        switch (sub) {
            case "help":
                if (!parsed.positionals.isEmpty() || parsed.force || parsed.hard) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu help usage. Run /yu help."));
                    return;
                }
                sendHelp(sender);
                return;
            case "init":
                if (!parsed.positionals.isEmpty() || parsed.force || parsed.hard) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu init usage. Run /yu help."));
                    return;
                }
                runInit(server, sender);
                return;
            case "remote":
                runRepo(server, sender, parsed);
                return;
            case "diff":
                runDiff(server, sender, parsed);
                return;
            case "debug-dialog":
                if (!parsed.positionals.isEmpty() || parsed.force || parsed.hard) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu debug-dialog usage. Run /yu help."));
                    return;
                }
                openDebugDialog(sender);
                return;
            case "fetch":
                if (parsed.force || parsed.hard || parsed.positionals.size() > 1) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu fetch usage. Run /yu help."));
                    return;
                }
                runNetworkOperation(server, sender, "fetch", false, parsed.positionals.isEmpty() ? "origin" : parsed.positionals.get(0));
                return;
            case "push":
                if (parsed.hard || parsed.positionals.size() > 0) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu push usage. Run /yu help."));
                    return;
                }
                runNetworkOperation(server, sender, "push", parsed.force, "origin");
                return;
            case "pull":
                if (!parsed.positionals.isEmpty() || parsed.force) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu pull usage. Run /yu help."));
                    return;
                }
                runNetworkOperation(server, sender, parsed.hard ? "pull-hard" : "pull", false, "origin");
                return;
            case "merge":
                if (!parsed.positionals.isEmpty() || parsed.force || parsed.hard) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu merge usage. Run /yu help."));
                    return;
                }
                runNetworkOperation(server, sender, "merge", false, "origin");
                return;
            case "reset":
                runReset(server, sender, parsed);
                return;
            default:
                sender.sendMessage(formatMessage(TextFormatting.RED, "Unknown /yu subcommand: " + sub + ". Run /yu help."));
                return;
        }
    }

    private void sendHelp(ICommandSender sender) {
        sender.sendMessage(formatMessage(TextFormatting.AQUA, "Available /yu commands:"));
        sendPlainLine(sender, TextFormatting.WHITE, "/yu init");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu remote add <url>");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup <help|save|list|details|restore|worlds|status>");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu diff");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu debug-dialog");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu fetch [remote]");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu push [--force]");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu pull [--hard]");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu merge");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu reset --hard <ref>");
    }

    private void runDiff(MinecraftServer server, ICommandSender sender, ParsedArgs parsed) {
        if (parsed.force || parsed.hard || !parsed.positionals.isEmpty()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu diff usage. Run /yu help."));
            return;
        }

        if (!yugetGITConfig.visualDiff.enabled) {
            sender.sendMessage(formatMessage(TextFormatting.YELLOW, "Visual diff is disabled in config."));
            return;
        }

        String worldKey = resolveWorldKey(server);
        if (worldKey == null || worldKey.trim().isEmpty()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Unable to resolve world for visual diff."));
            return;
        }

        VisualDiffSessionManager.Mode currentMode = VisualDiffSessionManager.getMode(worldKey);
        String action = currentMode == VisualDiffSessionManager.Mode.DIFF ? "off" : "on";

        switch (action) {
            case "on":
                refreshVisualDiffSnapshot(sender, worldKey);
                return;
            case "off":
                VisualDiffSessionManager.disable(worldKey);
                return;
            default:
                sender.sendMessage(formatMessage(TextFormatting.RED, "Unknown /yu diff mode: " + action));
                sender.sendMessage(plainMessage(TextFormatting.WHITE, "Use /yu diff."));
        }
    }

    private void refreshVisualDiffSnapshot(ICommandSender sender, String worldKey) {
        Integer centerX = null;
        Integer centerY = null;
        Integer centerZ = null;
        if (sender.getCommandSenderEntity() instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) sender.getCommandSenderEntity();
            centerX = (int) Math.floor(player.posX);
            centerY = (int) Math.floor(player.posY);
            centerZ = (int) Math.floor(player.posZ);
        }

        VisualDiffSessionManager.DiffSnapshot snapshot = VisualDiffSessionManager.computeSnapshot(
            worldKey,
            centerX,
            centerY,
            centerZ,
            yugetGITConfig.visualDiff.maxOverlayDistanceBlocks,
            yugetGITConfig.visualDiff.maxOverlayBlocks
        );
        VisualDiffSessionManager.enable(worldKey, snapshot);
    }

    private void runBackupSubcommand(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String[] backupArgs;
        if (args.length == 1) {
            backupArgs = new String[] {"help"};
        } else {
            backupArgs = Arrays.copyOfRange(args, 1, args.length);
        }
        backupCommand.execute(server, sender, backupArgs);
    }

    private void openDebugDialog(ICommandSender sender) {
        sender.sendMessage(formatMessage(TextFormatting.AQUA, "Opening debug dialog"));
        BackgroundExecutor.execute(() -> {
            try {
                GitBootstrap.resolveFromPath();
                boolean gitResolved = GitBootstrap.isGitResolved();
                boolean lfsResolved = com.yugetGIT.config.StateProperties.isGitLfsAvailable();

                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    if (gitResolved && lfsResolved) {
                        javax.swing.JOptionPane.showMessageDialog(
                            null,
                            "Git and Git-LFS are already configured.\nNo bootstrap action is required.",
                            "yugetGIT Debug Dialog",
                            javax.swing.JOptionPane.INFORMATION_MESSAGE
                        );
                        return;
                    }

                    com.yugetGIT.prelauncher.PreLaunchGitDialog.showDialog();
                });
            } catch (Exception e) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Debug dialog failed: " + e.getMessage()));
            }
        });
    }

    private void runReset(MinecraftServer server, ICommandSender sender, ParsedArgs parsed) {
        if (!parsed.hard || parsed.positionals.size() != 1 || parsed.force) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Invalid /yu reset usage. Use /yu reset --hard <ref>."));
            return;
        }

        File repoDir = resolveWorldRepo(server);
        if (!ensureInitializedRepo(repoDir, sender, "Run /yu init first.")) {
            return;
        }

        String targetRef = parsed.positionals.get(0).trim();
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

    private void runRepo(MinecraftServer server, ICommandSender sender, ParsedArgs parsed) {
        if (parsed.force || parsed.hard || parsed.positionals.isEmpty() || !"add".equalsIgnoreCase(parsed.positionals.get(0))) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Unknown remote command. Run /yu help."));
            return;
        }

        if (parsed.positionals.size() < 2) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Missing URL. Usage: /yu remote add <url>"));
            return;
        }

        String worldKey = resolveWorldKey(server);
        File repoDir = resolveWorldRepo(server);
        if (!ensureInitializedRepo(repoDir, sender, "Run /yu init first before adding a remote.")) {
            return;
        }

        String remoteUrl = String.join(" ", parsed.positionals.subList(1, parsed.positionals.size())).trim();
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
            String existingOrigin = getRemoteUrl(repoDir, "origin");
            GitExecutor.GitResult setResult;
            if (existingOrigin != null && !existingOrigin.isEmpty()) {
                setResult = GitExecutor.execute(repoDir, 10, "remote", "set-url", "origin", normalizedRemoteUrl);
            } else {
                setResult = GitExecutor.execute(repoDir, 10, "remote", "add", "origin", normalizedRemoteUrl);
            }

            if (!setResult.isSuccess()) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to set origin remote."));
                sender.sendMessage(formatMessage(TextFormatting.RED, shortGitError(setResult)));
                return;
            }

            String worldBranch = buildWorldBranch(worldKey);
            refreshMainMetadataAfterRemoteAdd(repoDir, worldBranch, sender);
            pushMainBranchToOrigin(repoDir);

            if (checkoutOrCreateBranch(repoDir, worldBranch)) {
                sender.sendMessage(formatMessage(TextFormatting.GREEN, "Remote added. Run /yu push to sync."));
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
        if (!ensureInitializedRepo(repoDir, sender, "Run /yu init first.")) {
            return;
        }

        String originUrl = getRemoteUrl(repoDir, remoteName);
        if ((originUrl == null || originUrl.isEmpty()) && "origin".equals(remoteName)) {
            originUrl = ensureRemoteConfiguredFromDefault(repoDir, sender, remoteName);
        }
        if (originUrl == null || originUrl.isEmpty()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Remote '" + remoteName + "' not found. Run /yu remote add <url>."));
            return;
        }

        String targetBranch = resolveOperationBranch(server, repoDir);

        sender.sendMessage(formatMessage(TextFormatting.WHITE, "Starting git " + operation + "..."));
        int networkTimeoutSeconds = resolveNetworkTimeoutSeconds();
        sendNetworkTimeoutHintIfNeeded(sender, networkTimeoutSeconds);

        if ("pull-hard".equals(operation)) {
            runHardPull(server, sender, repoDir, remoteName, targetBranch);
            return;
        }

        BossInfoServer networkBar = createNetworkBossBar(sender, operation);
        bumpNetworkBossBar(server, networkBar, operation, 15, null);
        BackgroundExecutor.execute(() -> {
            try {
                String branch = targetBranch;

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
                    sendScheduled(server, sender, plainMessage(TextFormatting.WHITE, "Run /yu pull (or /yu merge), resolve conflicts if needed, then /yu push again."));
                    sendScheduled(server, sender, plainMessage(TextFormatting.WHITE, "Use /yu push --force only if you want to overwrite remote history."));
                    return;
                }

                if (("pull".equals(operation) || "merge".equals(operation)) && loweredError.contains("unstaged changes")) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.YELLOW, "Pull blocked by local unstaged changes."));
                    sendScheduled(server, sender, plainMessage(TextFormatting.WHITE, "Create a backup or reset local state, then retry pull/merge."));
                    return;
                }

                if ("pull".equals(operation) && (loweredError.contains("could not apply") || loweredError.contains("conflict") || loweredError.contains("rebase"))) {
                    try {
                        GitExecutor.execute(repoDir, 10, "rebase", "--abort");
                    } catch (Exception ignored) {
                    }
                    sendScheduled(server, sender, formatMessage(TextFormatting.YELLOW, "Pull hit binary conflicts and was aborted automatically."));
                    sendScheduled(server, sender, plainMessage(TextFormatting.WHITE, "Use /yu pull --hard to force-sync your world branch to remote state."));
                    return;
                }

                if ("merge".equals(operation) && (loweredError.contains("automatic merge failed") || loweredError.contains("conflict"))) {
                    try {
                        GitExecutor.execute(repoDir, 10, "merge", "--abort");
                    } catch (Exception ignored) {
                    }
                    sendScheduled(server, sender, formatMessage(TextFormatting.YELLOW, "Merge hit binary conflicts and was aborted automatically."));
                    sendScheduled(server, sender, plainMessage(TextFormatting.WHITE, "Use /yu pull (rebase) or /yu push --force depending on which history you want to keep."));
                    return;
                }

                sendScheduled(server, sender, formatMessage(TextFormatting.RED, errorText));
                if ("push".equals(operation) && errorText.toLowerCase().contains("src refspec") && errorText.toLowerCase().contains("does not match any")) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.YELLOW, "No local commit exists yet. Run /yu backup save first."));
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
        boolean insecureTlsAllowed = isInsecureTlsAllowed();
        OsDetector.OS detectedOs = OsDetector.detectOS();
        cmdArgs.add(gitExe);
        if (insecureTlsAllowed) {
            cmdArgs.addAll(Arrays.asList("-c", "http.sslVerify=false"));
        }

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
        env.put("GCM_INTERACTIVE", "Never");
        env.put("GIT_ASKPASS", "echo");
        env.put("SSH_ASKPASS", "echo");
        if (insecureTlsAllowed) {
            env.put("GIT_SSL_NO_VERIFY", "1");
        }

        File binDir = PlatformPaths.getYugetGITDir().resolve("bin").toFile();
        if (binDir.exists() && binDir.isDirectory()) {
            String currentPath = env.getOrDefault("PATH", "");
            String pathSeparator = detectedOs == OsDetector.OS.WINDOWS ? ";" : ":";
            env.put("PATH", binDir.getAbsolutePath() + pathSeparator + currentPath);
        }

        if (detectedOs == OsDetector.OS.WINDOWS && GitBootstrap.isGitResolved()) {
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
        String worldKey = resolveWorldKey(server);
        File repoDir = resolveWorldRepo(server);
        sendScheduled(server, sender, formatMessage(TextFormatting.WHITE, "Initializing repository..."));
        BackgroundExecutor.execute(() -> {
            try {
                if (!repoDir.exists() && !repoDir.mkdirs()) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, "Failed to create world backup directory."));
                    return;
                }

                File gitDir = new File(repoDir, ".git");
                if (!gitDir.exists()) {
                    RepoConfig.initAndConfigure(repoDir);
                } else {
                    RepoConfig.ensureOperationalConfig(repoDir);
                }

                String worldBranch = buildWorldBranch(worldKey);
                boolean worldBranchAlreadyExists = hasBranch(repoDir, worldBranch);

                String headBeforeInit = resolveHeadCommit(repoDir);
                if (!ensureWorldBranchReference(repoDir, worldBranch, headBeforeInit)) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, "Failed to create/update world branch: " + worldBranch));
                    return;
                }

                if (!rebuildMainMetadataBranch(repoDir, worldBranch, sender)) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, "Failed to rebuild main metadata branch."));
                    return;
                }

                if (!hasBranch(repoDir, worldBranch) && !createBranchIfMissing(repoDir, worldBranch)) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, "Failed to create world branch: " + worldBranch));
                    return;
                }

                if (!worldBranchAlreadyExists) {
                    initializeWorldBranchWithoutMetadata(repoDir, worldBranch);
                }

                applyConfiguredDefaultOrigin(repoDir);
                if (!ensureMainBranchReady(repoDir)) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, "Failed to finalize main branch commit."));
                    return;
                }

                String originUrl = getRemoteUrl(repoDir, "origin");
                sendScheduled(server, sender, formatMessage(TextFormatting.WHITE, "Init complete."));
                sendScheduled(server, sender, new TextComponentString(TextFormatting.GOLD + "  origin: " + TextFormatting.WHITE
                    + (originUrl == null || originUrl.trim().isEmpty() ? "(not configured)" : originUrl)));
                sendScheduled(server, sender, new TextComponentString(TextFormatting.GOLD + "  branch: " + TextFormatting.WHITE + worldBranch));
                sendScheduled(server, sender, new TextComponentString(TextFormatting.GOLD + "  next: " + TextFormatting.WHITE + "/yu backup save -m \"first backup\""));
                sendScheduled(server, sender, new TextComponentString(TextFormatting.GOLD + "  optional: " + TextFormatting.WHITE + "/yu remote add <url>"));
                sendScheduled(server, sender, new TextComponentString(TextFormatting.GOLD + "  optional 2: " + TextFormatting.WHITE + "/yu push or /yu pull"));
            } catch (Exception e) {
                sendScheduled(server, sender, formatMessage(TextFormatting.RED, "yu init failed: " + e.getMessage()));
            }
        });
    }

    private boolean checkoutOrCreateBranch(File repoDir, String branchName) {
        try {
            if (GitExecutor.execute(repoDir, 15, "checkout", branchName).isSuccess()) {
                return true;
            }

            if (GitExecutor.execute(repoDir, 15, "checkout", "-b", branchName).isSuccess()) {
                return true;
            }

            return hasBranch(repoDir, branchName)
                && GitExecutor.execute(repoDir, 15, "checkout", branchName).isSuccess();
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

            if (resolveHeadCommit(repoDir) == null) {
                ensureLocalCommitIdentity(repoDir);
                GitExecutor.execute(repoDir, 10, "commit", "--allow-empty", "-m", "Initialize main branch");
            }

            return hasBranch(repoDir, MAIN_BRANCH) || checkoutOrCreateBranch(repoDir, MAIN_BRANCH);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ensureMainBranchCommitted(File repoDir) {
        try {
            if (!hasBranch(repoDir, MAIN_BRANCH)) {
                if (!GitExecutor.execute(repoDir, 15, "checkout", "--orphan", MAIN_BRANCH).isSuccess()) {
                    return false;
                }
            } else if (!checkoutOrCreateBranch(repoDir, MAIN_BRANCH)) {
                return false;
            }

            if (resolveHeadCommit(repoDir) != null) {
                return true;
            }

            ensureLocalCommitIdentity(repoDir);
            GitExecutor.execute(repoDir, 10, "add", "-A");
            GitExecutor.GitResult commitResult = GitExecutor.execute(repoDir, 10, "commit", "--allow-empty", "-m", "Initialize main branch");
            if (!commitResult.isSuccess()) {
                return false;
            }

            return resolveHeadCommit(repoDir) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ensureMainBranchReady(File repoDir) {
        return checkoutOrCreateBranch(repoDir, MAIN_BRANCH)
            && ensureMainBranchCommitted(repoDir)
            && MAIN_BRANCH.equals(resolveCurrentBranch(repoDir));
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

            File branchIndexPath = new File(metadataDirectory, "branch-index.md");
            if (branchIndexPath.exists()) {
                try {
                    Files.delete(branchIndexPath.toPath());
                } catch (Exception ignored) {
                }
            }

            File readmePath = new File(repoDir, README_FILE);
            saveRepositoryReadme(readmePath);
        } catch (Exception ignored) {
        }
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

    private void saveRepositoryReadme(File readmePath) {
        List<String> lines = new ArrayList<>();
        lines.add("# yugetGIT World Repository");
        lines.add("yugetGIT is a backup mod for minecraft that uses git and git lfs to manage world snapshots.");
        lines.add("");
        lines.add("- Version: **0.1.0**");
        lines.add("- This file is generated by `/yu init`.");
        lines.add("");
        lines.add("![yugetGIT icon](./.yugetgit/mod-icon.png)");

        try {
            Files.write(readmePath.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private void commitRepositoryMetadataIfNeeded(File repoDir, ICommandSender sender) {
        try {
            GitExecutor.GitResult statusResult = GitExecutor.execute(repoDir, 10, "status", "--porcelain");
            if (!statusResult.isSuccess() || statusResult.stdout == null || statusResult.stdout.trim().isEmpty()) {
                return;
            }

            GitExecutor.execute(repoDir, 10, "add", README_FILE, METADATA_DIR);
            GitExecutor.GitResult stagedMetadata = GitExecutor.execute(repoDir, 10,
                "diff", "--cached", "--name-only", "--", README_FILE, METADATA_DIR);
            if (!stagedMetadata.isSuccess() || stagedMetadata.stdout == null || stagedMetadata.stdout.trim().isEmpty()) {
                return;
            }

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
        return new TextComponentString(TextFormatting.GOLD + "[yugetGIT]  " + color + text);
    }

    private TextComponentString plainMessage(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }

    private void sendPlainLine(ICommandSender sender, TextFormatting color, String text) {
        sender.sendMessage(plainMessage(color, text));
    }

    private String shortGitError(GitExecutor.GitResult result) {
        if (result == null) {
            return "Unknown git error.";
        }
        String text = null;
        if (result.stderr != null && !result.stderr.trim().isEmpty()) {
            text = result.stderr.trim();
        } else if (result.stdout != null && !result.stdout.trim().isEmpty()) {
            text = result.stdout.trim();
        }
        if (text != null) {
            String[] lines = text.split("\\n");
            for (String line : lines) {
                String normalized = line == null ? "" : line.trim();
                if (!normalized.isEmpty() && !normalized.toLowerCase().startsWith("hint:")) {
                    return normalized.length() > 220 ? normalized.substring(0, 220) + "..." : normalized;
                }
            }
            return lines.length > 0 ? lines[0] : text;
        }
        return "Unknown git error.";
    }

    private void runHardPull(MinecraftServer server, ICommandSender sender, File repoDir, String remoteName, String branch) {
        BackgroundExecutor.execute(() -> {
            try {
                GitExecutor.GitResult fetchResult = GitExecutor.execute(repoDir, resolveNetworkTimeoutSeconds(), "fetch", remoteName, "--prune");
                if (!fetchResult.isSuccess()) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, "git pull-hard failed."));
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, shortGitError(fetchResult)));
                    return;
                }

                try {
                    GitExecutor.execute(repoDir, 10, "rebase", "--abort");
                } catch (Exception ignored) {
                }

                GitExecutor.GitResult resetResult = GitExecutor.execute(repoDir, resolveNetworkTimeoutSeconds(),
                    "reset", "--hard", remoteName + "/" + branch);
                if (resetResult.isSuccess()) {
                    sendScheduled(server, sender, formatMessage(TextFormatting.GREEN, "git pull --hard succeeded."));
                } else {
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, "git pull --hard failed."));
                    sendScheduled(server, sender, formatMessage(TextFormatting.RED, shortGitError(resetResult)));
                }
            } catch (Exception e) {
                sendScheduled(server, sender, formatMessage(TextFormatting.RED, "git pull --hard failed: " + e.getMessage()));
            }
        });
    }

    private File resolveWorldRepo(MinecraftServer server) {
        return PlatformPaths.getWorldsDir().resolve(resolveWorldKey(server)).toFile();
    }

    private String resolveWorldKey(MinecraftServer server) {
        return server.getEntityWorld().getSaveHandler().getWorldDirectory().getName();
    }

    private String resolveWorldBranch(MinecraftServer server) {
        return buildWorldBranch(resolveWorldKey(server));
    }

    private GitExecutor.GitResult pushMainBranchToOrigin(File repoDir) {
        try {
            if (!ensureMainBranchReady(repoDir)) {
                return new GitExecutor.GitResult(1, "", "failed to prepare main branch");
            }

            return GitExecutor.execute(repoDir, resolveNetworkTimeoutSeconds(), "push", "-u", "origin", MAIN_BRANCH);
        } catch (Exception e) {
            return new GitExecutor.GitResult(1, "", e.getMessage());
        }
    }

    private String resolveOperationBranch(MinecraftServer server, File repoDir) {
        String worldBranch = resolveWorldBranch(server);
        if (checkoutOrCreateBranch(repoDir, worldBranch)) {
            return worldBranch;
        }
        return resolveCurrentBranch(repoDir);
    }

    private boolean ensureInitializedRepo(File repoDir, ICommandSender sender, String message) {
        if (new File(repoDir, ".git").exists()) {
            return true;
        }
        sender.sendMessage(formatMessage(TextFormatting.RED, message));
        return false;
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
        return MAIN_BRANCH;
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

        String title = networkOperationLabel(operation) + "...";

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
            String prefix = networkOperationLabel(operation);
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
            String prefix = networkOperationLabel(operation);
            String suffix = (details == null || details.trim().isEmpty()) ? "" : (" | " + details.trim());
            bar.setName(new TextComponentString(TextFormatting.GOLD + prefix + "..." + suffix));
        });
    }

    private String networkOperationLabel(String operation) {
        if ("fetch".equals(operation)) {
            return "Fetching commits";
        }
        if ("push".equals(operation)) {
            return "Pushing commits";
        }
        if ("merge".equals(operation)) {
            return "Merging commits";
        }
        return "Pulling commits";
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

    private void applyConfiguredDefaultOrigin(File repoDir) {
        String defaultRemoteUrl = yugetGITConfig.gitNetwork.defaultRemoteUrl == null ? "" : yugetGITConfig.gitNetwork.defaultRemoteUrl.trim();
        if (defaultRemoteUrl.isEmpty()) {
            return;
        }

        try {
            String existingOrigin = getRemoteUrl(repoDir, "origin");
            if (existingOrigin != null && !existingOrigin.trim().isEmpty()) {
                return;
            }

            String normalizedRemoteUrl = normalizeRemoteUrl(defaultRemoteUrl);
            GitExecutor.GitResult addResult = GitExecutor.execute(repoDir, 10, "remote", "add", "origin", normalizedRemoteUrl);
            if (addResult.isSuccess()) {
                return;
            }
        } catch (Exception ignored) {
        }
    }

    private String ensureRemoteConfiguredFromDefault(File repoDir, ICommandSender sender, String remoteName) {
        if (!"origin".equals(remoteName)) {
            return null;
        }

        String defaultRemoteUrl = yugetGITConfig.gitNetwork.defaultRemoteUrl == null ? "" : yugetGITConfig.gitNetwork.defaultRemoteUrl.trim();
        if (defaultRemoteUrl.isEmpty()) {
            return null;
        }

        String normalized = normalizeRemoteUrl(defaultRemoteUrl);
        try {
            GitExecutor.GitResult addResult = GitExecutor.execute(repoDir, 10, "remote", "add", "origin", normalized);
            if (addResult.isSuccess()) {
                sender.sendMessage(formatMessage(TextFormatting.GREEN, "Auto-configured origin from settings."));
                return normalized;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void sendNetworkTimeoutHintIfNeeded(ICommandSender sender, int timeoutSeconds) {
        boolean alwaysShow = yugetGITConfig.gitNetwork.showNetworkTimeoutHintEveryTime;
        if (!alwaysShow && networkTimeoutHintShown) {
            return;
        }

        sender.sendMessage(formatMessage(TextFormatting.GRAY,
            "If the remote does not respond, this command times out after " + timeoutSeconds + " seconds."));
        if (!alwaysShow) {
            networkTimeoutHintShown = true;
        }
    }
}
