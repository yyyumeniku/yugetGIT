package com.yugetGIT.ui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.common.Loader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class YugetGITConfigGui extends GuiConfig {

    private static final int HUD_EDITOR_BUTTON_ID = 2990;

    public YugetGITConfigGui(GuiScreen parentScreen) {
        super(parentScreen, buildConfigElements(), "yugetgit", false, false, "yugetGIT Settings");
    }

    @Override
    public void initGui() {
        super.initGui();
        int buttonWidth = 190;
        int x = (this.width - buttonWidth) / 2;
        int y = this.height - 56;
        this.buttonList.add(new GuiButton(HUD_EDITOR_BUTTON_ID, x, y, buttonWidth, 20, "Open HUD Placement Editor"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == HUD_EDITOR_BUTTON_ID) {
            mc.displayGuiScreen(new VisualDiffHudEditorGui(this));
            return;
        }
        super.actionPerformed(button);
    }

    private static List<IConfigElement> buildConfigElements() {
        File configFile = new File(Loader.instance().getConfigDir(), "yugetgit.cfg");
        Configuration configuration = new Configuration(configFile);
        configuration.load();

        List<IConfigElement> elements = new ArrayList<>();
        addCategoryIfPresent(configuration, elements, "visualdiff");
        addCategoryIfPresent(configuration, elements, "backup");
        addCategoryIfPresent(configuration, elements, "gitnetwork");
        if (elements.isEmpty()) {
            for (String categoryName : configuration.getCategoryNames()) {
                if ("general".equalsIgnoreCase(categoryName)) {
                    continue;
                }
                elements.add(new ConfigElement(configuration.getCategory(categoryName)));
            }
        }
        return elements;
    }

    private static void addCategoryIfPresent(Configuration configuration,
                                             List<IConfigElement> elements,
                                             String categoryName) {
        if (configuration.hasCategory(categoryName)) {
            elements.add(new ConfigElement(configuration.getCategory(categoryName)));
        }
    }
}
