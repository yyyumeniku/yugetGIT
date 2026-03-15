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
        @Config.Comment("Enable auto-commit on world save after the first manual /yu backup save exists")
        public boolean autoCommitOnSave = false;

        @Config.Comment("Backup interval in minutes for automatic backups; 0 disables interval-based backups")
        @Config.RangeInt(min = 0, max = 1440)
        public int backupIntervalMinutes = 0;

        @Config.Comment("When true, /yu backup save skips if player movement since last manual backup is minimal. Use /yu backup save --force to force.")
        public boolean manualSaveMovementGuardEnabled = true;

        @Config.Comment("Track naturally occurring crop growth as dirty chunk changes for backups")
        public boolean trackCropGrowthChanges = true;

        @Config.Comment("Ignore block state toggles that are often interaction noise (doors, buttons, pressure plates, trapdoors, fence gates)")
        public boolean ignoreInteractiveStateToggles = true;
    }

    public static class GitNetworkSettings {
        @Config.Comment("Timeout in seconds for /yu network git commands (push/pull/fetch/merge)")
        @Config.RangeInt(min = 5, max = 3600)
        public int yuCommandTimeoutSeconds = 60;

        @Config.Comment("Allow insecure TLS for /yu network commands (self-signed HTTPS remotes)")
        public boolean allowInsecureTls = false;

        @Config.Comment("Default origin URL automatically applied by /yu init when no remote exists")
        public String defaultRemoteUrl = "";

        @Config.Comment("Automatically run git fetch --prune on world load and report result in chat")
        public boolean autoFetchOnWorldStart = true;

        @Config.Comment("Show the network timeout hint on every /yu fetch|push|pull|merge command. If false, show once per game session.")
        public boolean showNetworkTimeoutHintEveryTime = false;
    }

    @SubscribeEvent
    public static void onConfigChangedEvent(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals("yugetgit")) {
            ConfigManager.sync("yugetgit", Config.Type.INSTANCE);
        }
    }
}