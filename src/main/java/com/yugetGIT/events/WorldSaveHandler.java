package com.yugetGIT.events;

import com.yugetGIT.config.StateProperties;
import com.yugetGIT.config.yugetGITConfig;
import com.yugetGIT.core.git.CommitBuilder;
import com.yugetGIT.core.git.RepoConfig;
import com.yugetGIT.YugetGITMod;
import com.yugetGIT.util.PlatformPaths;
import com.yugetGIT.util.SaveEventGuard;
import net.minecraft.world.WorldServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import java.util.function.Consumer;
import net.minecraft.util.text.TextComponentString;
import com.yugetGIT.util.BlockEntitySnapshotManager;
import com.yugetGIT.util.EntitySnapshotManager;

public class WorldSaveHandler {

    private static final long AUTO_SAVE_STARTUP_GRACE_MS = 15000L;
    private static final double AUTO_COMMIT_MIN_MOVEMENT_SQ = 9.0D;

    private long lastBackupTime = System.currentTimeMillis();
    private final AtomicBoolean exitSaveTriggered = new AtomicBoolean(false);
    private final Map<String, Long> worldLoadedAt = new ConcurrentHashMap<>();
    private boolean hasLastSeenPlayerPosition = false;
    private double lastSeenPlayerX;
    private double lastSeenPlayerY;
    private double lastSeenPlayerZ;
    private boolean hasLastAutoCommitPosition = false;
    private double lastAutoCommitX;
    private double lastAutoCommitY;
    private double lastAutoCommitZ;

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld().isRemote || !(event.getWorld() instanceof WorldServer)) {
            return;
        }

        File worldDir = ((WorldServer) event.getWorld()).getSaveHandler().getWorldDirectory();
        worldLoadedAt.put(worldDir.getName(), System.currentTimeMillis());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!StateProperties.isBackupsEnabled()) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        updateLastSeenPlayerPosition(server);
        
        int intervalMinutes = yugetGITConfig.backup.commitIntervalMinutes;
        if (intervalMinutes <= 0) return;
        
        long now = System.currentTimeMillis();
        if (now - lastBackupTime >= (intervalMinutes * 60L * 1000L)) {
            lastBackupTime = now;

            if (server != null && server.getEntityWorld() != null) {
                WorldServer world = (WorldServer) server.getEntityWorld();
                File worldDir = world.getSaveHandler().getWorldDirectory();
                String worldKey = worldDir.getName();
                File repoDir = PlatformPaths.getWorldsDir().resolve(worldKey).toFile();
                if (!hasManualBackupInitialized(repoDir)) {
                    return;
                }
                if (!hasMeaningfulPlayerMovement()) {
                    return;
                }
                runCommitForWorld(server, world, "Auto-commit (Interval " + intervalMinutes + "m)");
            }
        }
    }

    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (!StateProperties.isBackupsEnabled() || event.getWorld().isRemote) return;
        if (SaveEventGuard.isSuppressed()) return;
        if (!yugetGITConfig.backup.autoCommitOnSave) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        WorldServer world = (WorldServer) event.getWorld();
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (isIntegratedPauseSave(server)) return;

        File worldDir = world.getSaveHandler().getWorldDirectory();
        String worldKey = worldDir.getName();
        Long loadedAt = worldLoadedAt.get(worldKey);
        if (loadedAt != null && (System.currentTimeMillis() - loadedAt) < AUTO_SAVE_STARTUP_GRACE_MS) {
            return;
        }
        if (!hasMeaningfulPlayerMovement()) {
            return;
        }
        File repoDir = PlatformPaths.getWorldsDir().resolve(worldKey).toFile();
        if (!hasManualBackupInitialized(repoDir)) return;

        runCommitForWorld(server, world, "Auto-commit on save");
    }

    public void handleServerStopping(MinecraftServer server) {
        if (server == null || !server.isSinglePlayer()) return;
        if (!StateProperties.isBackupsEnabled()) return;
        if (!yugetGITConfig.backup.autoCommitOnSave) return;
        if (!exitSaveTriggered.compareAndSet(false, true)) return;

        if (!(server.getEntityWorld() instanceof WorldServer)) {
            exitSaveTriggered.set(false);
            return;
        }

        WorldServer world = (WorldServer) server.getEntityWorld();
        if (!hasMeaningfulPlayerMovement()) {
            exitSaveTriggered.set(false);
            return;
        }
        File worldDir = world.getSaveHandler().getWorldDirectory();
        String worldKey = worldDir.getName();
        File repoDir = PlatformPaths.getWorldsDir().resolve(worldKey).toFile();
        if (!hasManualBackupInitialized(repoDir)) {
            YugetGITMod.LOGGER.info("[yugetGIT] Exit auto-save skipped during server stop: manual backup repository not initialized for world {}", worldKey);
            exitSaveTriggered.set(false);
            return;
        }

        YugetGITMod.LOGGER.info("[yugetGIT] Exit auto-save triggered during server stop for world {}", worldKey);
        runCommitForWorld(server, world, "Auto-commit on exit");
    }

    private boolean isIntegratedPauseSave(MinecraftServer server) {
        if (!(server instanceof IntegratedServer)) {
            return false;
        }

        try {
            Field pausedField = IntegratedServer.class.getDeclaredField("isGamePaused");
            pausedField.setAccessible(true);
            return pausedField.getBoolean(server);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasManualBackupInitialized(File repoDir) {
        File gitDir = new File(repoDir, ".git");
        return gitDir.exists();
    }

    private void runCommitForWorld(MinecraftServer server, WorldServer world, String message) {
        final boolean resetExitFlagOnReturn = "Auto-commit on exit".equals(message);
        final boolean markAutoCommitBaselineOnReturn = message.startsWith("Auto-commit");

        if (server == null || world == null) {
            if (resetExitFlagOnReturn) exitSaveTriggered.set(false);
            return;
        }

        File worldDir = world.getSaveHandler().getWorldDirectory();
        String worldKey = worldDir.getName();
        File repoDir = PlatformPaths.getWorldsDir().resolve(worldKey).toFile();
        if (!repoDir.exists() && !repoDir.mkdirs()) {
            if (resetExitFlagOnReturn) exitSaveTriggered.set(false);
            return;
        }

        File gitDir = new File(repoDir, ".git");
        if (!gitDir.exists()) {
            try {
                RepoConfig.initAndConfigure(repoDir);
            } catch (Exception e) {
                e.printStackTrace();
                if (resetExitFlagOnReturn) exitSaveTriggered.set(false);
                return;
            }
        }

        try {
            RepoConfig.ensureOperationalConfig(repoDir);
        } catch (Exception e) {
            e.printStackTrace();
            if (resetExitFlagOnReturn) exitSaveTriggered.set(false);
            return;
        }

        server.getPlayerList().saveAllPlayerData();

        final boolean wasSavingDisabled = world.disableLevelSaving;
        try {
            SaveEventGuard.enter();
            world.saveAllChunks(true, null);
            world.disableLevelSaving = true;
            EntitySnapshotManager.capture(server, repoDir);
            BlockEntitySnapshotManager.capture(server, repoDir);
        } catch (Exception e) {
            world.disableLevelSaving = wasSavingDisabled;
            e.printStackTrace();
            if (resetExitFlagOnReturn) exitSaveTriggered.set(false);
            return;
        } finally {
            SaveEventGuard.exit();
        }

        Consumer<String> feedback = msg -> {
            String lower = msg.toLowerCase();
            boolean important = lower.contains("committed") || lower.contains("failed") || lower.contains("error") || lower.contains("skipped");
            if (important) {
                server.getPlayerList().sendMessage(new TextComponentString("\u00A76[yugetGIT] \u00A7d" + msg));
            }
        };
        final String trigger = resolveAutoTrigger(message);
        final int onlinePlayers = server.getPlayerList() == null ? 0 : server.getPlayerList().getCurrentPlayerCount();

        Runnable completion = () -> {
            if (resetExitFlagOnReturn) {
                exitSaveTriggered.set(false);
            }
            if (markAutoCommitBaselineOnReturn) {
                markAutoCommitBaseline();
            }
            try {
                server.addScheduledTask(() -> world.disableLevelSaving = wasSavingDisabled);
            } catch (Exception ignored) {
                world.disableLevelSaving = wasSavingDisabled;
            }
        };
        CommitBuilder.AutoMessageContext autoMessageContext = new CommitBuilder.AutoMessageContext(worldKey, trigger, onlinePlayers);
        new CommitBuilder(repoDir, worldDir, message, autoMessageContext).commitAsync(feedback, completion);
    }

    private String resolveAutoTrigger(String message) {
        if (message == null) {
            return "WORLD_SAVE";
        }

        String normalized = message.toLowerCase();
        if (normalized.contains("exit")) {
            return "SERVER_STOP";
        }
        if (normalized.contains("save") || normalized.contains("interval")) {
            return "WORLD_SAVE";
        }

        return "WORLD_SAVE";
    }

    private void updateLastSeenPlayerPosition(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null) {
            return;
        }

        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }

        EntityPlayerMP player = server.getPlayerList().getPlayers().get(0);
        lastSeenPlayerX = player.posX;
        lastSeenPlayerY = player.posY;
        lastSeenPlayerZ = player.posZ;
        hasLastSeenPlayerPosition = true;

        if (!hasLastAutoCommitPosition) {
            lastAutoCommitX = lastSeenPlayerX;
            lastAutoCommitY = lastSeenPlayerY;
            lastAutoCommitZ = lastSeenPlayerZ;
            hasLastAutoCommitPosition = true;
        }
    }

    private boolean hasMeaningfulPlayerMovement() {
        if (!hasLastSeenPlayerPosition) {
            return true;
        }
        if (!hasLastAutoCommitPosition) {
            return true;
        }

        double dx = lastSeenPlayerX - lastAutoCommitX;
        double dy = lastSeenPlayerY - lastAutoCommitY;
        double dz = lastSeenPlayerZ - lastAutoCommitZ;
        return (dx * dx + dy * dy + dz * dz) >= AUTO_COMMIT_MIN_MOVEMENT_SQ;
    }

    private void markAutoCommitBaseline() {
        if (!hasLastSeenPlayerPosition) {
            return;
        }

        lastAutoCommitX = lastSeenPlayerX;
        lastAutoCommitY = lastSeenPlayerY;
        lastAutoCommitZ = lastSeenPlayerZ;
        hasLastAutoCommitPosition = true;
    }
}