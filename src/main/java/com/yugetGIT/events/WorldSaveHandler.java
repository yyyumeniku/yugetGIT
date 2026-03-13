package com.yugetGIT.events;

import com.yugetGIT.config.StateProperties;
import com.yugetGIT.config.yugetGITConfig;
import com.yugetGIT.core.git.CommitBuilder;
import com.yugetGIT.core.git.RepoConfig;
import com.yugetGIT.util.PlatformPaths;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import java.util.function.Consumer;
import net.minecraft.util.text.TextComponentString;

public class WorldSaveHandler {

    private long lastBackupTime = System.currentTimeMillis();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!StateProperties.isBackupsEnabled()) return;
        
        int intervalMinutes = yugetGITConfig.backup.commitIntervalMinutes;
        if (intervalMinutes <= 0) return;
        
        long now = System.currentTimeMillis();
        if (now - lastBackupTime >= (intervalMinutes * 60L * 1000L)) {
            lastBackupTime = now;

            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server != null && server.getEntityWorld() != null) {
                WorldServer world = (WorldServer) server.getEntityWorld();
                runCommitForWorld(server, world, "Auto-commit (Interval " + intervalMinutes + "m)");
            }
        }
    }

    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (!StateProperties.isBackupsEnabled() || event.getWorld().isRemote) return;
        if (!yugetGITConfig.backup.autoCommitOnSave) return;
        
        if (event.getWorld() instanceof WorldServer) {
            WorldServer world = (WorldServer) event.getWorld();
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            runCommitForWorld(server, world, "Auto-commit on save");
        }
    }

    private void runCommitForWorld(MinecraftServer server, WorldServer world, String message) {
        if (server == null || world == null) {
            return;
        }

        File worldDir = world.getSaveHandler().getWorldDirectory();
        String worldKey = worldDir.getName();
        File repoDir = PlatformPaths.getWorldsDir().resolve(worldKey).toFile();
        if (!repoDir.exists() && !repoDir.mkdirs()) {
            return;
        }

        File gitDir = new File(repoDir, ".git");
        if (!gitDir.exists()) {
            try {
                RepoConfig.initAndConfigure(repoDir);
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }

        try {
            RepoConfig.ensureOperationalConfig(repoDir);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        server.getPlayerList().saveAllPlayerData();

        final boolean wasSavingDisabled = world.disableLevelSaving;
        try {
            world.saveAllChunks(true, null);
            world.disableLevelSaving = true;
        } catch (Exception e) {
            world.disableLevelSaving = wasSavingDisabled;
            e.printStackTrace();
            return;
        }

        Consumer<String> feedback = msg -> server.getPlayerList().sendMessage(new TextComponentString("\u00A7d" + msg));
        Runnable completion = () -> server.addScheduledTask(() -> world.disableLevelSaving = wasSavingDisabled);
        new CommitBuilder(repoDir, worldDir, message).commitAsync(feedback, completion);
    }
}