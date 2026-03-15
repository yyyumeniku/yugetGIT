package com.yugetGIT.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import com.yugetGIT.core.git.GitCredentialChecker;
import com.yugetGIT.core.git.CommitBuilder;
import com.yugetGIT.core.git.GitExecutor;
import com.yugetGIT.core.git.GitLfsManager;
import com.yugetGIT.core.git.RepoConfig;
import com.yugetGIT.config.yugetGITConfig;
import com.yugetGIT.util.BackgroundExecutor;
import com.yugetGIT.util.SaveEventGuard;
import com.yugetGIT.util.BlockEntitySnapshotManager;
import com.yugetGIT.util.EntitySnapshotManager;
import com.yugetGIT.util.SaveProgressTracker;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BackupCommand extends CommandBase {

    private static final double MANUAL_SAVE_MIN_MOVEMENT_SQ = 9.0D;
    private static final Pattern DETAIL_PATTERN = Pattern.compile("^(\\S+)\\s+world=\\\"([^\\\"]+)\\\"\\s+chunks=(\\d+)\\s+players=(\\d+)\\s+at=(.+)$");
    private static final Map<String, ManualSaveBaseline> MANUAL_SAVE_BASELINES = new ConcurrentHashMap<>();

    private static final class ManualSaveBaseline {
        private final double x;
        private final double y;
        private final double z;

        private ManualSaveBaseline(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class PreparationResult {
        private final boolean success;
        private final boolean previousDisableLevelSaving;
        private final String errorMessage;

        private PreparationResult(boolean success, boolean previousDisableLevelSaving, String errorMessage) {
            this.success = success;
            this.previousDisableLevelSaving = previousDisableLevelSaving;
            this.errorMessage = errorMessage;
        }
    }

    @Override
    public String getName() {
        return "backup";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/yu backup <help|save|list|details|worlds|restore|status>";
    }
    
    @Override
    public int getRequiredPermissionLevel() {
        return 0; // Allow in survival
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
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

    private static class ParsedArgs {
        int count = -1;
        boolean all = false;
        boolean start = false;
        String hash = null;
        String userString = null;
        boolean valid = true;
        String error = "";
        boolean force = false;
        
        static ParsedArgs parse(String[] args) {
            ParsedArgs p = new ParsedArgs();
            for (int i = 1; i < args.length; i++) {
                String a = args[i].toLowerCase();
                if (a.equals("-all")) p.all = true;
                else if (a.equals("-start")) p.start = true;
                else if (a.equals("-force") || a.equals("--force")) p.force = true;
                else if (a.equals("-hash")) {
                    if (i + 1 >= args.length) {
                        p.valid = false;
                        p.error = "Missing value for -hash.";
                        break;
                    }
                    p.hash = args[++i];
                }
                else if (a.equals("-m")) {
                    if (i + 1 >= args.length) {
                        p.valid = false;
                        p.error = "Missing value for -m.";
                        break;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int j = i + 1; j < args.length; j++) {
                        sb.append(args[j]).append(j == args.length - 1 ? "" : " ");
                    }
                    String raw = sb.toString().trim();
                    if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                        raw = raw.substring(1, raw.length() - 1).trim();
                    }
                    p.userString = raw;
                    break;
                }
                else if (a.startsWith("-")) {
                    try {
                        p.count = Integer.parseInt(a.substring(1));
                    } catch (Exception ignore) {
                        p.valid = false;
                        p.error = "Unknown flag: " + a;
                        break;
                    }
                } else {
                    p.valid = false;
                    p.error = "Unexpected argument: " + args[i];
                    break;
                }
            }
            if (p.all) p.count = Integer.MAX_VALUE;
            return p;
        }
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
            return;
        }

        File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
        String worldKey = worldDir.getName();
        File repoDir = com.yugetGIT.util.PlatformPaths.getWorldsDir().resolve(worldKey).toFile();
        
        String sub = args[0].toLowerCase();
        ParsedArgs parsed = "worlds".equals(sub) ? new ParsedArgs() : ParsedArgs.parse(args);
        if (!parsed.valid) {
            sender.sendMessage(formatMessage(TextFormatting.RED, parsed.error));
            return;
        }
        if (!validateArgsForSubcommand(sender, sub, parsed)) {
            return;
        }

        switch (sub) {
            case "help":
                sendHelp(sender);
                break;
            case "save":
                String customTitle = parsed.userString == null ? "" : parsed.userString.trim();
                boolean hasCustomTitle = !customTitle.isEmpty();
                String saveTitle = hasCustomTitle ? customTitle : "Manual save";
                runManualSave(server, sender, repoDir, worldDir, saveTitle, parsed.force, null, !hasCustomTitle);
                break;
                
            case "list":
                if (!hasRepository(sender, repoDir)) {
                    return;
                }
                try {
                    GitExecutor.GitResult result = GitExecutor.execute(repoDir, 10, "log", "--format=%H\u001F%s");
                    if (result.isSuccess() && !result.stdout.trim().isEmpty()) {
                        List<String> lines = new ArrayList<>(Arrays.asList(result.stdout.trim().split("\n")));
                        Collections.reverse(lines); // oldest -> newest for stable numbering

                        int total = lines.size();
                        int limit = parsed.count == -1 ? 10 : parsed.count;
                        int showCount = Math.min(limit, total);
                        sender.sendMessage(formatMessage(TextFormatting.GREEN, "Backups: showing " + showCount + " / " + total + " commits."));

                        int startIndex = parsed.start ? 0 : Math.max(0, total - showCount);
                        appendRemotePendingCommits(sender, repoDir, total);

                        for (int displayIndex = startIndex + showCount - 1; displayIndex >= startIndex; displayIndex--) {
                            int restoreNumber = displayIndex + 1;
                            String logLine = lines.get(displayIndex).trim();
                            if (!logLine.isEmpty()) {
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
                                    detailsButton.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/yu backup details -hash " + hash));
                                    detailsButton.getStyle().setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        new TextComponentString(TextFormatting.GRAY + "Show compact commit details")));
                                    lineComponent.appendSibling(detailsButton);
                                    sender.sendMessage(lineComponent);
                                } else {
                                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "#" + restoreNumber + " " + logLine));
                                }
                            }
                        }
                    } else {
                        sender.sendMessage(formatMessage(TextFormatting.GRAY, "No backups found."));
                    }
                } catch (Exception e) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to fetch backups."));
                }
                break;

            case "details":
                if (!hasRepository(sender, repoDir)) {
                    return;
                }
                showCommitDetails(sender, repoDir, parsed);
                break;
                
            case "worlds":
                if (args.length >= 3 && args[1].equalsIgnoreCase("delete")) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 2; i < args.length; i++) {
                        sb.append(args[i]).append(i == args.length - 1 ? "" : " ");
                    }
                    String target = sb.toString().replace("\"", "").trim();
                    File targetDir = com.yugetGIT.util.PlatformPaths.getWorldsDir().resolve(target).toFile();
                    if (targetDir.exists()) {
                        try {
                            FileUtils.deleteDirectory(targetDir);
                            sender.sendMessage(formatMessage(TextFormatting.GREEN, "Deleted backups for world: " + target));
                        } catch (Exception e) {
                            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to delete: " + e.getMessage()));
                        }
                    } else {
                        sender.sendMessage(formatMessage(TextFormatting.RED, "World backup '" + target + "' not found!"));
                    }
                } else {
                    sender.sendMessage(formatMessage(TextFormatting.AQUA, "Stored Backups:"));
                    File[] worlds = com.yugetGIT.util.PlatformPaths.getWorldsDir().toFile().listFiles();
                    if (worlds != null && worlds.length > 0) {
                        for (File w : worlds) {
                            if (w.isDirectory()) {
                                long size = FileUtils.sizeOfDirectory(w) / (1024 * 1024);
                                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + w.getName() + TextFormatting.DARK_GRAY + " (" + size + " MB)"));
                            }
                        }
                    } else {
                        sender.sendMessage(formatMessage(TextFormatting.GRAY, "No world backups found."));
                    }
                }
                break;
                
            case "restore":
                if (!hasRepository(sender, repoDir)) {
                    return;
                }
                if (parsed.hash == null && parsed.count <= 0) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Specify a backup hash (-hash id) or backup number (-1 = first listed commit)."));
                    return;
                }

                String targetRef;
                if (parsed.hash != null) {
                    targetRef = sanitizeCommitRef(parsed.hash);
                } else {
                    int requestedNumber = parsed.count;
                    targetRef = buildRestoreRefFromNumber(repoDir, requestedNumber);
                    if (targetRef == null) {
                        sender.sendMessage(formatMessage(TextFormatting.RED, "Backup number out of range. Run /yu backup list first."));
                        return;
                    }
                }
                runRestoreInPlace(server, sender, repoDir, worldDir, targetRef);
                break;
            case "push":
                sender.sendMessage(formatMessage(TextFormatting.YELLOW, "Use /yu push for remote sync."));
                break;
            case "pull":
                sender.sendMessage(formatMessage(TextFormatting.YELLOW, "Use /yu pull for remote sync."));
                break;
            case "status":
                sendStatus(sender, repoDir);
                break;
            default:
                sender.sendMessage(formatMessage(TextFormatting.RED, "ERROR: Invalid /yu backup command: '" + sub + "'. Cannot safely execute."));
                sendPlainLine(sender, TextFormatting.RED, "Available: help, save, list, details, worlds, restore, status");
                break;
        }
    }

    private void sendHelp(ICommandSender sender) {
        sender.sendMessage(formatMessage(TextFormatting.AQUA, "Available /yu backup commands:"));
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup save -m \"message text\"");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup save [--force] -m \"message text\"");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup list [-all] [-start] [-(number)]");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup details -hash <id>");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup restore [-hash <id>] [-(number)]");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup worlds");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup worlds delete \"World Folder\"");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu backup status");
        sendPlainLine(sender, TextFormatting.WHITE, "/yu debug-dialog");
    }

    private void sendStatus(ICommandSender sender, File repoDir) {
        boolean userConfigured = GitCredentialChecker.hasUserName() && GitCredentialChecker.hasUserEmail();
        sender.sendMessage(formatMessage(TextFormatting.AQUA, "Backup status:"));
        sendPlainLine(sender, TextFormatting.WHITE, "Identity Configured: " + (userConfigured ? TextFormatting.GREEN + "Yes" : TextFormatting.RED + "No"));
        sendPlainLine(sender, TextFormatting.WHITE, "Git Resolved: " + (com.yugetGIT.core.git.GitBootstrap.isGitResolved() ? TextFormatting.GREEN + "Yes" : TextFormatting.RED + "No"));
        sendPlainLine(sender, TextFormatting.WHITE, "Git-LFS Available: " + (com.yugetGIT.config.StateProperties.isGitLfsAvailable() ? TextFormatting.GREEN + "Yes" : TextFormatting.RED + "No"));
        
        File gitDir = new File(repoDir, ".git");
        sendPlainLine(sender, TextFormatting.WHITE, "Repository Built: " + (gitDir.exists() ? TextFormatting.GREEN + "Yes" : TextFormatting.RED + "No"));
        if (gitDir.exists()) {
            sendPlainLine(sender, TextFormatting.WHITE, "LFS Tracking Rules: " + (GitLfsManager.hasRequiredTrackingRules(repoDir) ? TextFormatting.GREEN + "Ready" : TextFormatting.YELLOW + "Missing (.gitattributes not fully configured)"));
        }
    }

    private void showCommitDetails(ICommandSender sender, File repoDir, ParsedArgs parsed) {
        String ref = null;
        if (parsed.hash != null && !parsed.hash.trim().isEmpty()) {
            ref = sanitizeCommitRef(parsed.hash).trim();
        } else if (parsed.count > 0) {
            ref = buildRestoreRefFromNumber(repoDir, parsed.count);
        }

        if (ref == null || ref.isEmpty()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Use /yu backup details -hash <id> (or /yu backup details -<number>)."));
            return;
        }

        try {
            GitExecutor.GitResult result = GitExecutor.execute(repoDir, 10, "log", "-1", "--date=iso-local", "--format=%H\n%an\n%ad\n%s\n%b", ref);
            if (!result.isSuccess() || result.stdout == null || result.stdout.trim().isEmpty()) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Could not load commit details for: " + ref));
                return;
            }

            String[] lines = result.stdout.split("\n");
            String hash = lines.length > 0 ? lines[0].trim() : ref;
            String author = lines.length > 1 ? lines[1].trim() : "unknown";
            String committedAt = lines.length > 2 ? lines[2].trim() : "unknown";
            String subject = lines.length > 3 ? lines[3].trim() : "(no subject)";
            String body = lines.length > 4 ? String.join("\n", Arrays.copyOfRange(lines, 4, lines.length)).trim() : "";
            String shortHash = hash.length() > 10 ? hash.substring(0, 10) : hash;

            sender.sendMessage(formatMessage(TextFormatting.AQUA, "Commit " + shortHash));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "|- title: " + TextFormatting.WHITE + subject));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "|- author: " + TextFormatting.WHITE + author));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "|- date: " + TextFormatting.WHITE + committedAt));
            if (!body.isEmpty()) {
                String[] bodyLines = body.split("\n");
                Matcher detailMatcher = DETAIL_PATTERN.matcher(bodyLines[0].trim());
                if (detailMatcher.matches()) {
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "|- stats: " + TextFormatting.WHITE + detailMatcher.group(1)));
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "|- world: " + TextFormatting.WHITE + detailMatcher.group(2)));
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "|- chunks: " + TextFormatting.WHITE + detailMatcher.group(3)));
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "|- players: " + TextFormatting.WHITE + detailMatcher.group(4)));
                    boolean hasExtraLines = bodyLines.length > 1;
                    String whenPrefix = hasExtraLines ? "|- at: " : "`- at: ";
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + whenPrefix + TextFormatting.WHITE + detailMatcher.group(5)));

                    for (int i = 1; i < bodyLines.length; i++) {
                        String extra = bodyLines[i].trim();
                        if (extra.isEmpty()) {
                            continue;
                        }
                        String branch = (i == bodyLines.length - 1) ? "`- " : "|- ";
                        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + branch + TextFormatting.WHITE + extra));
                    }
                } else {
                    for (int i = 0; i < bodyLines.length; i++) {
                        String bodyLine = bodyLines[i].trim();
                        if (bodyLine.isEmpty()) {
                            continue;
                        }
                        String branch = (i == bodyLines.length - 1) ? "`- " : "|- ";
                        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + branch + TextFormatting.WHITE + bodyLine));
                    }
                }
            }
        } catch (Exception e) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to read commit details: " + e.getMessage()));
        }
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

    private String sanitizeCommitRef(String rawRef) {
        if (rawRef == null) {
            return "";
        }
        return rawRef.replace("<", "").replace(">", "");
    }

    private void appendRemotePendingCommits(ICommandSender sender, File repoDir, int localCommitCount) {
        try {
            GitExecutor.GitResult branchResult = GitExecutor.execute(repoDir, 10, "rev-parse", "--abbrev-ref", "HEAD");
            if (!branchResult.isSuccess() || branchResult.stdout == null || branchResult.stdout.trim().isEmpty()) {
                return;
            }

            String branch = branchResult.stdout.trim();
            GitExecutor.GitResult remoteLog = GitExecutor.execute(repoDir, 10, "log", "--pretty=format:%H\u001F%s", "-5", "HEAD..refs/remotes/origin/" + branch);
            if (!remoteLog.isSuccess() || remoteLog.stdout == null || remoteLog.stdout.trim().isEmpty()) {
                return;
            }

            String[] rows = remoteLog.stdout.trim().split("\\n");
            int remoteNumber = localCommitCount + rows.length;
            for (String row : rows) {
                String line = row == null ? "" : row.trim();
                if (!line.isEmpty()) {
                    String[] parts = line.split("\\u001F", 2);
                    if (parts.length == 2) {
                        String hash = parts[0].trim();
                        String shortHash = hash.length() > 7 ? hash.substring(0, 7) : hash;
                        String messagePreview = abbreviate(parts[1].trim(), 66);

                        TextComponentString lineComponent = new TextComponentString(
                            TextFormatting.DARK_GRAY + "#" + remoteNumber + " "
                                + TextFormatting.GRAY + shortHash + TextFormatting.DARK_GRAY + "  "
                                + TextFormatting.GRAY + messagePreview + " "
                        );

                        TextComponentString detailsButton = new TextComponentString(TextFormatting.AQUA + "[details]");
                        detailsButton.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/yu backup details -hash " + hash));
                        detailsButton.getStyle().setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new TextComponentString(TextFormatting.GRAY + "Show compact commit details")));
                        lineComponent.appendSibling(detailsButton);
                        sender.sendMessage(lineComponent);
                        remoteNumber--;
                    } else {
                        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + line));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void runManualSave(MinecraftServer server, ICommandSender sender, File repoDir, File worldDir, String message, boolean force, String customNote, boolean useAutoMessage) {
        String worldKey = worldDir.getName();
        if (!force
            && yugetGITConfig.backup.manualSaveMovementGuardEnabled
            && !hasMeaningfulManualSaveMovement(server, worldKey)) {
            sender.sendMessage(formatMessage(TextFormatting.YELLOW, "No changes detected, backup skipped. If you still want a backup, use /yu backup save --force."));
            return;
        }

        sender.sendMessage(formatMessage(TextFormatting.WHITE, force ? "Starting forced backup..." : "Starting backup..."));

        if (!repoDir.exists() && !repoDir.mkdirs()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to create world backup directory."));
            return;
        }

        File gitDir = new File(repoDir, ".git");
        if (!gitDir.exists()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Repository is not initialized yet. Run /yu init first."));
            return;
        }

        try {
            RepoConfig.ensureOperationalConfig(repoDir);
        } catch (Exception e) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Git-LFS setup failed: " + e.getMessage()));
            return;
        }

        String worldBranch = buildWorldBranch(worldKey);
        if (!checkoutOrCreateBranch(repoDir, worldBranch)) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to switch to world branch: " + worldBranch));
            return;
        }

        World senderWorld = sender.getEntityWorld();
        WorldServer activeWorld = senderWorld instanceof WorldServer ? (WorldServer) senderWorld : (WorldServer) server.getEntityWorld();
        if (activeWorld == null) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Unable to resolve active world for /yu backup save."));
            return;
        }

        BossInfoServer bossBar = createBossBarFor(sender);
        SaveProgressTracker.start("Preparing world save", 0, "Flushing chunks and snapshotting entities");

        BackgroundExecutor.execute(() -> {
            PreparationResult prep = prepareWorldStateForBackup(server, activeWorld, repoDir);
            if (!prep.success) {
                SaveProgressTracker.finish(false, prep.errorMessage);
                try {
                    server.addScheduledTask(() -> {
                        sender.sendMessage(formatMessage(TextFormatting.RED, prep.errorMessage));
                        clearBossBar(sender, bossBar);
                    });
                } catch (Exception ignored) {
                }
                return;
            }

            Consumer<String> feedback = msg -> sendProgress(sender, msg, repoDir);
            Consumer<CommitBuilder.ProgressSnapshot> progress = snapshot -> {
                SaveProgressTracker.update(snapshot.getStage(), snapshot.getPercent(), snapshot.getChangedChunks(), snapshot.getBytesWritten());
                updateBossBar(server, sender, bossBar, snapshot);
            };
            Runnable completion = () -> {
                markManualSaveBaseline(server, worldKey);
                restoreSavingFlag(server, activeWorld, prep.previousDisableLevelSaving);
                clearBossBar(sender, bossBar);
            };

            int onlinePlayers = server.getPlayerList() == null ? 0 : server.getPlayerList().getCurrentPlayerCount();
            String trigger = force ? "MANUAL_FORCE_SAVE" : "MANUAL_SAVE";
            String note = customNote != null && !customNote.trim().isEmpty() ? customNote.trim() : null;
            CommitBuilder.AutoMessageContext autoMessageContext = useAutoMessage
                ? new CommitBuilder.AutoMessageContext(worldKey, trigger, onlinePlayers, note)
                : null;

            new CommitBuilder(repoDir, worldDir, message, autoMessageContext).commitAsync(feedback, progress, completion);
        });
    }

    private boolean hasMeaningfulManualSaveMovement(MinecraftServer server, String worldKey) {
        EntityPlayerMP player = getPrimaryPlayer(server);
        if (player == null) {
            return true;
        }

        ManualSaveBaseline baseline = MANUAL_SAVE_BASELINES.get(worldKey);
        if (baseline == null) {
            return true;
        }

        double dx = player.posX - baseline.x;
        double dy = player.posY - baseline.y;
        double dz = player.posZ - baseline.z;
        return (dx * dx + dy * dy + dz * dz) >= MANUAL_SAVE_MIN_MOVEMENT_SQ;
    }

    private void markManualSaveBaseline(MinecraftServer server, String worldKey) {
        EntityPlayerMP player = getPrimaryPlayer(server);
        if (player == null) {
            return;
        }

        MANUAL_SAVE_BASELINES.put(worldKey, new ManualSaveBaseline(player.posX, player.posY, player.posZ));
    }

    private EntityPlayerMP getPrimaryPlayer(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null || server.getPlayerList().getPlayers().isEmpty()) {
            return null;
        }
        return server.getPlayerList().getPlayers().get(0);
    }

    private boolean checkoutOrCreateBranch(File repoDir, String branchName) {
        try {
            if (GitExecutor.execute(repoDir, 15, "checkout", branchName).isSuccess()) {
                return true;
            }

            return GitExecutor.execute(repoDir, 15, "checkout", "-b", branchName).isSuccess();
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

    private PreparationResult prepareWorldStateForBackup(MinecraftServer server, WorldServer activeWorld, File repoDir) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicBoolean previousDisableSaving = new AtomicBoolean(activeWorld.disableLevelSaving);
        AtomicReference<String> error = new AtomicReference<>("Failed to flush world/entity state before commit.");

        try {
            server.addScheduledTask(() -> {
                try {
                    previousDisableSaving.set(activeWorld.disableLevelSaving);
                    SaveEventGuard.enter();
                    activeWorld.saveAllChunks(true, null);
                    activeWorld.disableLevelSaving = true;
                    EntitySnapshotManager.capture(server, repoDir);
                    BlockEntitySnapshotManager.capture(server, repoDir);
                    success.set(true);
                } catch (Exception e) {
                    activeWorld.disableLevelSaving = previousDisableSaving.get();
                    error.set("Failed to flush world/entity state before commit: " + e.getMessage());
                } finally {
                    SaveEventGuard.exit();
                    latch.countDown();
                }
            });

            boolean completed = latch.await(180, TimeUnit.SECONDS);
            if (!completed) {
                return new PreparationResult(false, previousDisableSaving.get(), "Timed out while preparing world save.");
            }
            return new PreparationResult(success.get(), previousDisableSaving.get(), error.get());
        } catch (Exception e) {
            return new PreparationResult(false, previousDisableSaving.get(), "Failed to schedule world snapshot task: " + e.getMessage());
        }
    }

    private void restoreSavingFlag(MinecraftServer server, WorldServer world, boolean previousDisableLevelSaving) {
        try {
            server.addScheduledTask(() -> world.disableLevelSaving = previousDisableLevelSaving);
        } catch (Exception ignored) {
            world.disableLevelSaving = previousDisableLevelSaving;
        }
    }

    private void sendProgress(ICommandSender sender, String message, File repoDir) {
        String lower = message.toLowerCase();
        boolean important = lower.contains("failed") || lower.contains("error") || lower.contains("skipped") || lower.contains("committed");
        if (sender instanceof EntityPlayerMP && !important) {
            return;
        }

        String formatted = TextFormatting.GOLD + "[yugetGIT]  " + TextFormatting.LIGHT_PURPLE + message;
        if (sender instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            if (lower.contains("backup committed")) {
                TextComponentString line = new TextComponentString(formatted + " ");
                String commitHash = null;
                try {
                    GitExecutor.GitResult hashResult = GitExecutor.execute(repoDir, 10, "rev-parse", "--verify", "HEAD");
                    if (hashResult.isSuccess() && hashResult.stdout != null && !hashResult.stdout.trim().isEmpty()) {
                        commitHash = hashResult.stdout.trim();
                    }
                } catch (Exception ignored) {
                }

                if (commitHash != null) {
                    TextComponentString detailsButton = new TextComponentString(TextFormatting.AQUA + "[details]");
                    detailsButton.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/yu backup details -hash " + commitHash));
                    detailsButton.getStyle().setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new TextComponentString(TextFormatting.GRAY + "Show full commit details")));
                    line.appendSibling(detailsButton);
                }

                player.sendMessage(line);
                return;
            }

            player.sendMessage(new TextComponentString(formatted));
            return;
        }
        sender.sendMessage(new TextComponentString(formatted));
    }

    private BossInfoServer createBossBarFor(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) {
            return null;
        }

        BossInfoServer bar = new BossInfoServer(
            new TextComponentString(TextFormatting.LIGHT_PURPLE + "Preparing save..."),
            BossInfo.Color.GREEN,
            BossInfo.Overlay.NOTCHED_20
        );
        bar.setPercent(0.0f);
        bar.addPlayer((EntityPlayerMP) sender);
        return bar;
    }

    private void updateBossBar(MinecraftServer server, ICommandSender sender, BossInfoServer bar, CommitBuilder.ProgressSnapshot snapshot) {
        if (bar == null || !(sender instanceof EntityPlayerMP)) {
            return;
        }

        try {
            server.addScheduledTask(() -> {
                float progress = Math.max(0.0f, Math.min(1.0f, snapshot.getPercent() / 100.0f));
                bar.setPercent(progress);

                double mb = snapshot.getBytesWritten() / (1024.0 * 1024.0);
                String title = String.format(
                    "%s %d%% | chunks %d | %.2f MB",
                    snapshot.getStage(),
                    snapshot.getPercent(),
                    snapshot.getChangedChunks(),
                    mb
                );
                bar.setName(new TextComponentString(TextFormatting.LIGHT_PURPLE + title));
            });
        } catch (Exception ignored) {
        }
    }

    private void clearBossBar(ICommandSender sender, BossInfoServer bar) {
        if (bar == null || !(sender instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        try {
            if (player.connection != null) {
                bar.removePlayer(player);
            }
        } catch (Exception ignored) {
        }
    }

    private void runRestoreInPlace(MinecraftServer server, ICommandSender sender, File repoDir, File worldDir, String targetRef) {
        World senderWorld = sender.getEntityWorld();
        WorldServer activeWorld = senderWorld instanceof WorldServer ? (WorldServer) senderWorld : (WorldServer) server.getEntityWorld();
        if (activeWorld == null) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Unable to resolve active world for restore."));
            return;
        }

        final boolean wasSavingDisabled = activeWorld.disableLevelSaving;
        try {
            activeWorld.saveAllChunks(true, null);
            activeWorld.disableLevelSaving = true;
        } catch (Exception e) {
            activeWorld.disableLevelSaving = wasSavingDisabled;
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to freeze world for restore: " + e.getMessage()));
            return;
        }

        com.yugetGIT.util.BackgroundExecutor.execute(() -> {
            try {
                String resolvedRef = resolveRestoreRef(repoDir, targetRef, sender, server);
                if (resolvedRef == null) {
                    server.addScheduledTask(() -> {
                        sender.sendMessage(formatMessage(TextFormatting.RED, "Backup ref not found: " + targetRef));
                        activeWorld.disableLevelSaving = wasSavingDisabled;
                    });
                    return;
                }

                GitExecutor.GitResult checkoutResult = GitExecutor.execute(repoDir, 120, "checkout", resolvedRef, "--", "staging", "meta");
                if (!checkoutResult.isSuccess()) {
                    server.addScheduledTask(() -> {
                        sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to load backup files: " + checkoutResult.stderr));
                        activeWorld.disableLevelSaving = wasSavingDisabled;
                    });
                    return;
                }

                int rebuiltRegions = assembleRegionsFromStaging(repoDir, worldDir);
                com.yugetGIT.core.mca.ChunkTimestamp.clearAll();
                server.addScheduledTask(() -> {
                    try {
                        int restoredEntities = EntitySnapshotManager.apply(server, repoDir);
                        int restoredBlockEntities = BlockEntitySnapshotManager.apply(server, repoDir);
                        sender.sendMessage(formatMessage(TextFormatting.GREEN, "Restored " + rebuiltRegions + " region files, repositioned " + restoredEntities + " entities, and refreshed " + restoredBlockEntities + " block entities."));
                        scheduleRestoreKick(server, sender, activeWorld, 5, wasSavingDisabled);
                    } catch (Exception applyException) {
                        sender.sendMessage(formatMessage(TextFormatting.RED, "Restore apply phase failed: " + applyException.getMessage()));
                        activeWorld.disableLevelSaving = wasSavingDisabled;
                    }
                });
            } catch (Exception e) {
                server.addScheduledTask(() -> {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Restore failed: " + e.getMessage()));
                    activeWorld.disableLevelSaving = wasSavingDisabled;
                });
            }
        });
    }

    private void scheduleRestoreKick(MinecraftServer server, ICommandSender sender, WorldServer restoredWorld, int seconds, boolean previousDisableSavingState) {
        if (server.isSinglePlayer()) {
            restoredWorld.disableLevelSaving = previousDisableSavingState;
            sender.sendMessage(formatMessage(TextFormatting.YELLOW, "Restore applied in singleplayer. Re-open the world from menu if chunks look stale."));
            return;
        }

        List<UUID> playersToKick = new ArrayList<>();
        for (Object player : restoredWorld.playerEntities) {
            if (player instanceof EntityPlayerMP) {
                playersToKick.add(((EntityPlayerMP) player).getUniqueID());
            }
        }

        if (playersToKick.isEmpty()) {
            restoredWorld.disableLevelSaving = previousDisableSavingState;
            return;
        }

        BossInfoServer countdownBar = new BossInfoServer(
            new TextComponentString(TextFormatting.LIGHT_PURPLE + "Rejoin in " + seconds + "s"),
            BossInfo.Color.RED,
            BossInfo.Overlay.NOTCHED_20
        );

        PlayerList playerList = server.getPlayerList();
        for (UUID uuid : playersToKick) {
            EntityPlayerMP player = playerList.getPlayerByUUID(uuid);
            if (player != null) {
                countdownBar.addPlayer(player);
            }
        }

        com.yugetGIT.util.BackgroundExecutor.execute(() -> {
            for (int remaining = seconds; remaining >= 1; remaining--) {
                final int countdownSeconds = remaining;
                server.addScheduledTask(() -> {
                    float percent = Math.max(0.0f, Math.min(1.0f, countdownSeconds / (float) seconds));
                    countdownBar.setPercent(percent);
                    countdownBar.setName(new TextComponentString(TextFormatting.LIGHT_PURPLE + "Rejoin in " + countdownSeconds + "s"));
                });

                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    server.addScheduledTask(() -> {
                        for (UUID uuid : playersToKick) {
                            EntityPlayerMP player = playerList.getPlayerByUUID(uuid);
                            if (player != null) {
                                countdownBar.removePlayer(player);
                            }
                        }
                    });
                    return;
                }
            }

            server.addScheduledTask(() -> {
                for (UUID uuid : playersToKick) {
                    EntityPlayerMP livePlayer = playerList.getPlayerByUUID(uuid);
                    if (livePlayer == null || livePlayer.connection == null) {
                        continue;
                    }
                    try {
                        String disconnectMessage = TextFormatting.GREEN + "Restoration successful! You can enter your world again.";
                        livePlayer.connection.disconnect(new TextComponentString(disconnectMessage));
                    } catch (Exception ignored) {
                    } finally {
                        countdownBar.removePlayer(livePlayer);
                    }
                }
                restoredWorld.disableLevelSaving = previousDisableSavingState;
            });
        });
    }

    private String resolveRestoreRef(File repoDir, String targetRef, ICommandSender sender, MinecraftServer server) throws Exception {
        GitExecutor.GitResult exact = GitExecutor.execute(repoDir, 20, "rev-parse", "--verify", targetRef);
        if (exact.isSuccess()) {
            return targetRef;
        }

        if (targetRef.startsWith("HEAD~")) {
            GitExecutor.GitResult head = GitExecutor.execute(repoDir, 20, "rev-parse", "--verify", "HEAD");
            if (head.isSuccess()) {
                server.addScheduledTask(() -> sender.sendMessage(formatMessage(TextFormatting.YELLOW, "No older backup exists, restoring current HEAD snapshot instead.")));
                return "HEAD";
            }
        }

        return null;
    }

    private String buildRestoreRefFromNumber(File repoDir, int number) {
        if (number <= 0) {
            return null;
        }

        try {
            GitExecutor.GitResult countResult = GitExecutor.execute(repoDir, 10, "rev-list", "--count", "HEAD");
            if (!countResult.isSuccess()) {
                return null;
            }

            int totalCommits = Integer.parseInt(countResult.stdout.trim());
            if (number > totalCommits) {
                return null;
            }

            int headOffset = totalCommits - number;
            if (headOffset == 0) {
                return "HEAD";
            }
            return "HEAD~" + headOffset;
        } catch (Exception e) {
            return null;
        }
    }

    private int assembleRegionsFromStaging(File repoDir, File worldDir) throws Exception {
        File[] dims = {
            new File(repoDir, "staging/overworld/region"),
            new File(repoDir, "staging/DIM-1/region"),
            new File(repoDir, "staging/DIM1/region")
        };

        int restoredCount = 0;
        for (File dimStaging : dims) {
            if (!dimStaging.exists() || !dimStaging.isDirectory()) {
                continue;
            }

            String parentName = dimStaging.getParentFile().getName();
            String targetDimPrefix = "overworld".equals(parentName) ? "" : parentName + "/";
            File worldRegionDir = new File(worldDir, targetDimPrefix + "region");
            if (!worldRegionDir.exists()) {
                worldRegionDir.mkdirs();
            }

            List<java.nio.file.Path> regionDirs;
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dimStaging.toPath())) {
                regionDirs = stream.filter(java.nio.file.Files::isDirectory).collect(Collectors.toList());
            }

            for (java.nio.file.Path regionDir : regionDirs) {
                String mcaName = regionDir.toFile().getName() + ".mca";
                File outMca = new File(worldRegionDir, mcaName);
                com.yugetGIT.core.mca.ChunkAssembler.assembleRegionFile(regionDir, outMca.toPath());
                restoredCount++;
            }
        }

        return restoredCount;
    }

    private boolean validateArgsForSubcommand(ICommandSender sender, String sub, ParsedArgs parsed) {
        if ("save".equals(sub) && (parsed.all || parsed.start || parsed.hash != null || parsed.count != -1)) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "save only supports -m \"message\"."));
            return false;
        }
        if ("list".equals(sub) && (parsed.hash != null || parsed.userString != null)) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "list supports only -all, -start, and -<number>."));
            return false;
        }
        if ("restore".equals(sub) && (parsed.all || parsed.start || parsed.userString != null)) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "restore supports only -hash <id> or -<number>."));
            return false;
        }
        if (("status".equals(sub) || "help".equals(sub))
            && (parsed.all || parsed.start || parsed.hash != null || parsed.userString != null || parsed.count != -1)) {
            sender.sendMessage(formatMessage(TextFormatting.RED, sub + " does not take flags."));
            return false;
        }
        return true;
    }

    private boolean hasRepository(ICommandSender sender, File repoDir) {
        File gitDir = new File(repoDir, ".git");
        if (gitDir.exists()) {
            return true;
        }

        sender.sendMessage(formatMessage(TextFormatting.RED, "No backup repository exists for this world yet. Run /yu backup save first."));
        return false;
    }
}
