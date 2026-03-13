package com.yugetGIT.config;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "yugetgit")
@Mod.EventBusSubscriber(modid = "yugetgit")
public class yugetGITConfig {

    public static RepoSettings repo = new RepoSettings();
    public static BackupSettings backup = new BackupSettings();
    public static PushPullSettings sync = new PushPullSettings();

    public static class RepoSettings {
        public String repoMode = "PER_WORLD"; // SINGLE_REPO
    }

    public static class BackupSettings {
        public boolean autoCommitOnSave = false; // default false so pause doesn't trigger it
        public boolean commitOnPlayerDeath = false;
        public int commitIntervalMinutes = 0;
        public String[] ignoreList = new String[]{};
    }

    public static class PushPullSettings {
        public boolean pullOnLaunch = true;
        public int pullTimeoutSeconds = 300;
        public String pullRemote = "origin";
        public String pullBranch = "main";
        
        public boolean pushOnExit = false;
        public int pushAfterCommits = 0;
        public int pushTimeoutSeconds = 300;
        public String pushRemote = "origin";
        public String pushBranch = "main";
    }

    @SubscribeEvent
    public static void onConfigChangedEvent(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals("yugetgit")) {
            ConfigManager.sync("yugetgit", Config.Type.INSTANCE);
        }
    }
}