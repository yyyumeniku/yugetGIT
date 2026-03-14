package com.yugetGIT.config;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "yugetgit")
@Mod.EventBusSubscriber(modid = "yugetgit")
public class yugetGITConfig {

    public static BackupSettings backup = new BackupSettings();
    public static GitNetworkSettings gitNetwork = new GitNetworkSettings();

    public static class BackupSettings {
        @Config.Comment("Enable auto-commit on world save after the first manual /backup save exists")
        public boolean autoCommitOnSave = false;

        @Config.Comment("Minutes between automatic commits; 0 disables interval commits")
        @Config.RangeInt(min = 0, max = 1440)
        public int commitIntervalMinutes = 0;

        @Config.Comment("When true, /backup save skips if player movement since last manual backup is minimal. Use /backup fsave to force.")
        public boolean manualSaveMovementGuardEnabled = true;
    }

    public static class GitNetworkSettings {
        @Config.Comment("Timeout in seconds for /yu network git commands (push/pull/fetch/merge)")
        @Config.RangeInt(min = 5, max = 3600)
        public int yuCommandTimeoutSeconds = 60;

        @Config.Comment("Allow insecure TLS for /yu network commands (self-signed HTTPS remotes)")
        public boolean allowInsecureTls = false;
    }

    @SubscribeEvent
    public static void onConfigChangedEvent(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals("yugetgit")) {
            ConfigManager.sync("yugetgit", Config.Type.INSTANCE);
        }
    }
}