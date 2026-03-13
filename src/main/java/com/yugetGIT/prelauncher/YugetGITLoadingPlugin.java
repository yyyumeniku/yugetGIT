package com.yugetGIT.prelauncher;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("yugetGIT Pre-Launcher")
@IFMLLoadingPlugin.SortingIndex(1001)
public class YugetGITLoadingPlugin implements IFMLLoadingPlugin {

    public YugetGITLoadingPlugin() {
        System.out.println("[yugetGIT] FML CorePlugin initialized! Checking for Git...");
        PreLaunchGitDialog.showIfNeeded();
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null; // Could also execute here, but constructor is even earlier
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
