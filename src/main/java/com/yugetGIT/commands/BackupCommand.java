package com.yugetGIT.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import com.yugetGIT.core.git.GitCredentialChecker;
import com.yugetGIT.core.git.CommitBuilder;
import com.yugetGIT.core.git.GitExecutor;
import com.yugetGIT.core.git.GitLfsManager;
import com.yugetGIT.core.git.RepoConfig;
import com.yugetGIT.util.EntitySnapshotManager;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Consumer;

public class BackupCommand extends CommandBase {

    @Override
    public String getName() {
        return "backup";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/backup <help|save|list|worlds|restore|delete|push|pull|status>";
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
        return new TextComponentString(TextFormatting.GOLD + "[yugetGIT] " + color + text);
    }

    private static class ParsedArgs {
        int count = -1;
        boolean all = false;
        boolean start = false;
        String hash = null;
        String userString = null;
        boolean valid = true;
        String error = "";
        
        static ParsedArgs parse(String[] args, boolean strictTokens) {
            ParsedArgs p = new ParsedArgs();
            for (int i = 1; i < args.length; i++) {
                String a = args[i].toLowerCase();
                if (a.equals("-all")) p.all = true;
                else if (a.equals("-start")) p.start = true;
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
                    if (i + 2 > args.length - 1) {
                        String raw = args[i + 1];
                        boolean quoted = raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"");
                        if (!quoted) {
                            p.valid = false;
                            p.error = "Use -m with quotes, for example: -m \"my commit message\".";
                            break;
                        }
                        p.userString = raw.substring(1, raw.length() - 1).trim();
                        break;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int j = i + 1; j < args.length; j++) {
                        sb.append(args[j]).append(j == args.length - 1 ? "" : " ");
                    }
                    p.userString = sb.toString().replace("\"", "").trim();
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
                } else if (strictTokens) {
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
        ParsedArgs parsed = ParsedArgs.parse(args, !"worlds".equals(sub));
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
                sender.sendMessage(formatMessage(TextFormatting.WHITE, "Saving world state..."));
                String message = parsed.userString != null ? parsed.userString : "Manual save";
                runManualSave(server, sender, repoDir, worldDir, message);
                break;
                
            case "list":
                if (!hasRepository(sender, repoDir)) {
                    return;
                }
                sender.sendMessage(formatMessage(TextFormatting.GREEN, "Recent backups:"));
                try {
                    GitExecutor.GitResult result = GitExecutor.execute(repoDir, 10, "log", "--oneline");
                    if (result.isSuccess() && !result.stdout.trim().isEmpty()) {
                        List<String> lines = new ArrayList<>(Arrays.asList(result.stdout.trim().split("\n")));
                        if (parsed.start) Collections.reverse(lines);
                        
                        int limit = parsed.count == -1 ? 10 : parsed.count;
                        int end = Math.min(limit, lines.size());
                        sender.sendMessage(formatMessage(TextFormatting.WHITE, "Showing " + end + " / " + lines.size() + " commits:"));
                        for (int i = 0; i < end; i++) {
                            String logLine = lines.get(i).trim();
                            if (!logLine.isEmpty()) {
                                String[] parts = logLine.split(" ", 2);
                                if (parts.length == 2) {
                                    String hash = parts[0];
                                    String msg = parts[1];
                                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "- " + TextFormatting.YELLOW + hash + TextFormatting.GRAY + " " + msg));
                                } else {
                                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "- " + logLine));
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
                
            case "delete":
                if (!hasRepository(sender, repoDir)) {
                    return;
                }
                try {
                    if (parsed.all) {
                        FileUtils.deleteDirectory(new File(repoDir, ".git"));
                        sender.sendMessage(formatMessage(TextFormatting.AQUA, "Wiped entirely ALL repository commits."));
                    } else if (parsed.hash != null) {
                        GitExecutor.GitResult res = GitExecutor.execute(repoDir, 60, "reset", "--hard", parsed.hash);
                        if (res.isSuccess()) {
                            sender.sendMessage(formatMessage(TextFormatting.GREEN, "Deleted backups perfectly! Rolled back to exact backup: " + parsed.hash));
                        } else {
                            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to rollback: " + res.stderr));
                        }
                    } else if (parsed.start) {
                        // Deleting early commits basically means truncating history.
                        sender.sendMessage(formatMessage(TextFormatting.RED, "Git cannot natively 'delete start' without wiping early history securely. Use delete -all to clear completely."));
                    } else {
                        // Normal "-amount" deletion
                        int toDelete = parsed.count == -1 ? 1 : parsed.count;
                        GitExecutor.GitResult res = GitExecutor.execute(repoDir, 60, "reset", "--hard", "HEAD~" + toDelete);
                        if (res.isSuccess()) {
                            sender.sendMessage(formatMessage(TextFormatting.AQUA, "Safely rolled back & deleted the latest " + toDelete + " backups."));
                        } else {
                            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to rollback: " + res.stderr));
                        }
                    }
                    
                    // Cleanup staging folder to prevent uncompressed NBT blobs from wasting massive disk space
                    File stagingDir = new File(repoDir, "staging");
                    if (stagingDir.exists()) FileUtils.deleteDirectory(stagingDir);
                    
                    // Reset timestamps so next save sees modifications across time jumps
                    com.yugetGIT.core.mca.ChunkTimestamp.clearAll();
                    
                } catch (Exception e) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Delete error: " + e.getMessage()));
                }
                break;
                
            case "restore":
                if (!hasRepository(sender, repoDir)) {
                    return;
                }
                if (parsed.hash == null && parsed.count <= 0) {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Specify a backup hash (-hash id) or recent offset (-1)."));
                    return;
                }

                String targetRef = parsed.hash != null ? parsed.hash.replaceAll("[<>]", "") : ("HEAD~" + (parsed.count == -1 ? 1 : parsed.count));
                runRestoreInPlace(server, sender, repoDir, worldDir, targetRef);
                break;
            case "push":
                sender.sendMessage(formatMessage(TextFormatting.WHITE, "Initiating push..."));
                break;
            case "pull":
                sender.sendMessage(formatMessage(TextFormatting.WHITE, "Initiating pull..."));
                break;
            case "status":
                sendStatus(sender, repoDir);
                break;
            case "dialog":
            case "debug-dialog":
                sender.sendMessage(formatMessage(TextFormatting.AQUA, "Opening Pre-Launch Dialog on Host OS..."));
                new Thread(() -> {
                    try {
                        javax.swing.SwingUtilities.invokeAndWait(() -> com.yugetGIT.prelauncher.PreLaunchGitDialog.showDialog());
                    } catch (Exception e) {}
                }).start();
                break;
            default:
                sender.sendMessage(formatMessage(TextFormatting.RED, "ERROR: Invalid /backup command: '" + sub + "'. Cannot safely execute."));
                sender.sendMessage(formatMessage(TextFormatting.RED, "Available: help, save, list, worlds, restore, delete, push, pull, status"));
                break;
        }
    }

    private void sendHelp(ICommandSender sender) {
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/backup save -m \"message text\""));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/backup list [-all] [-start] [-(number)]"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/backup restore [-hash <id>] [-(number)]"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/backup delete [-all] [-hash <id>] [-(number)]"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/backup worlds"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/backup worlds delete \"World Folder\""));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/backup status"));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "/backup debug-dialog"));
    }

    private void sendStatus(ICommandSender sender, File repoDir) {
        boolean userConfigured = GitCredentialChecker.hasUserName() && GitCredentialChecker.hasUserEmail();
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "Identity Configured: " + (userConfigured ? TextFormatting.GREEN + "Yes" : TextFormatting.RED + "No")));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "Git Resolved: " + (com.yugetGIT.core.git.GitBootstrap.isGitResolved() ? TextFormatting.GREEN + "Yes" : TextFormatting.RED + "No")));
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "Git-LFS Available: " + (com.yugetGIT.config.StateProperties.isGitLfsAvailable() ? TextFormatting.GREEN + "Yes" : TextFormatting.RED + "No")));
        
        File gitDir = new File(repoDir, ".git");
        sender.sendMessage(formatMessage(TextFormatting.WHITE, "Repository Built: " + (gitDir.exists() ? TextFormatting.GREEN + "Yes" : TextFormatting.RED + "No")));
        if (gitDir.exists()) {
            sender.sendMessage(formatMessage(TextFormatting.WHITE, "LFS Tracking Rules: " + (GitLfsManager.hasRequiredTrackingRules(repoDir) ? TextFormatting.GREEN + "Ready" : TextFormatting.YELLOW + "Missing (.gitattributes not fully configured)")));
        }
    }

    private void runManualSave(MinecraftServer server, ICommandSender sender, File repoDir, File worldDir, String message) {
        if (!repoDir.exists() && !repoDir.mkdirs()) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to create world backup directory."));
            return;
        }

        File gitDir = new File(repoDir, ".git");
        if (!gitDir.exists()) {
            try {
                RepoConfig.initAndConfigure(repoDir);
            } catch (Exception e) {
                sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to initialize Git repository!"));
                e.printStackTrace();
                return;
            }
        }

        try {
            RepoConfig.ensureOperationalConfig(repoDir);
        } catch (Exception e) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Git-LFS setup failed: " + e.getMessage()));
            return;
        }

        World senderWorld = sender.getEntityWorld();
        WorldServer activeWorld = senderWorld instanceof WorldServer ? (WorldServer) senderWorld : (WorldServer) server.getEntityWorld();
        if (activeWorld == null) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "Unable to resolve active world for /backup save."));
            return;
        }

        boolean wasSavingDisabled = activeWorld.disableLevelSaving;
        try {
            activeWorld.saveAllChunks(true, null);
            activeWorld.disableLevelSaving = true;
            EntitySnapshotManager.capture(server, repoDir);
        } catch (Exception e) {
            activeWorld.disableLevelSaving = wasSavingDisabled;
            sender.sendMessage(formatMessage(TextFormatting.RED, "Failed to flush world/entity state before commit."));
            return;
        }

        BossInfoServer bossBar = createBossBarFor(sender);
        Consumer<String> feedback = msg -> sendProgress(sender, msg);
        Consumer<CommitBuilder.ProgressSnapshot> progress = snapshot -> updateBossBar(server, sender, bossBar, snapshot);
        Runnable completion = () -> server.addScheduledTask(() -> {
            activeWorld.disableLevelSaving = wasSavingDisabled;
            clearBossBar(sender, bossBar);
        });
        new CommitBuilder(repoDir, worldDir, message).commitAsync(feedback, progress, completion);
    }

    private void sendProgress(ICommandSender sender, String message) {
        String lower = message.toLowerCase();
        boolean important = lower.contains("failed") || lower.contains("error") || lower.contains("skipped") || lower.contains("committed");
        if (sender instanceof EntityPlayerMP && !important) {
            return;
        }

        String formatted = TextFormatting.GOLD + "[yugetGIT] " + TextFormatting.LIGHT_PURPLE + message;
        if (sender instanceof EntityPlayerMP) {
            ((EntityPlayerMP) sender).sendMessage(new TextComponentString(formatted));
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
    }

    private void clearBossBar(ICommandSender sender, BossInfoServer bar) {
        if (bar == null || !(sender instanceof EntityPlayerMP)) {
            return;
        }
        bar.removePlayer((EntityPlayerMP) sender);
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
                int restoredEntities = EntitySnapshotManager.apply(server, repoDir);
                com.yugetGIT.core.mca.ChunkTimestamp.clearAll();
                server.addScheduledTask(() -> {
                    sender.sendMessage(formatMessage(TextFormatting.GREEN, "Restored " + rebuiltRegions + " region files and repositioned " + restoredEntities + " entities."));
                    scheduleRestoreKick(server, activeWorld, 5, wasSavingDisabled);
                });
            } catch (Exception e) {
                server.addScheduledTask(() -> {
                    sender.sendMessage(formatMessage(TextFormatting.RED, "Restore failed: " + e.getMessage()));
                    activeWorld.disableLevelSaving = wasSavingDisabled;
                });
            }
        });
    }

    private void scheduleRestoreKick(MinecraftServer server, WorldServer restoredWorld, int seconds, boolean previousDisableSavingState) {
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
                    String disconnectMessage = TextFormatting.GREEN + "Restoration successful! You can enter your world again.";
                    livePlayer.connection.disconnect(new TextComponentString(disconnectMessage));
                    countdownBar.removePlayer(livePlayer);
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
        if ("delete".equals(sub) && parsed.userString != null) {
            sender.sendMessage(formatMessage(TextFormatting.RED, "delete does not support -m."));
            return false;
        }
        if (("status".equals(sub) || "help".equals(sub) || "push".equals(sub) || "pull".equals(sub) || "dialog".equals(sub) || "debug-dialog".equals(sub))
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

        sender.sendMessage(formatMessage(TextFormatting.RED, "No backup repository exists for this world yet. Run /backup save first."));
        return false;
    }
}
