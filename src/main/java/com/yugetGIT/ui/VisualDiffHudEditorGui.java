package com.yugetGIT.ui;

import com.yugetGIT.config.yugetGITConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

import java.io.IOException;

public class VisualDiffHudEditorGui extends GuiScreen {

    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;

    private final GuiScreen parentScreen;
    private GuiButton locationButton;
    private GuiTextField xField;
    private GuiTextField yField;

    public VisualDiffHudEditorGui(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();

        int panelWidth = BUTTON_WIDTH * 2 + BUTTON_GAP;
        int panelHeight = BUTTON_HEIGHT * 3 + BUTTON_GAP * 2;
        int panelX = width - panelWidth - 8;
        int panelY = isHudBottomRight() ? 8 : height - panelHeight - 8;

        int row1Y = panelY;
        int row2Y = row1Y + BUTTON_HEIGHT + BUTTON_GAP;
        int row3Y = row2Y + BUTTON_HEIGHT + BUTTON_GAP;
        int firstColumnX = panelX;
        int secondColumnX = firstColumnX + BUTTON_WIDTH + BUTTON_GAP;

        locationButton = new GuiButton(1, firstColumnX, row1Y, BUTTON_WIDTH * 2 + BUTTON_GAP, BUTTON_HEIGHT, "Location");
        buttonList.add(locationButton);

        xField = new GuiTextField(20, fontRenderer, firstColumnX, row2Y, BUTTON_WIDTH, BUTTON_HEIGHT);
        xField.setCanLoseFocus(true);
        xField.setMaxStringLength(5);

        yField = new GuiTextField(21, fontRenderer, secondColumnX, row2Y, BUTTON_WIDTH, BUTTON_HEIGHT);
        yField.setCanLoseFocus(true);
        yField.setMaxStringLength(5);

        buttonList.add(new GuiButton(22, firstColumnX, row3Y, BUTTON_WIDTH, BUTTON_HEIGHT, "Reset"));
        buttonList.add(new GuiButton(100, secondColumnX, row3Y, BUTTON_WIDTH, BUTTON_HEIGHT, "Done"));
        refreshButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 1:
                cycleLocation();
                refreshButtons();
                saveConfig();
                initGui();
                return;
            case 22:
                setTopLeftAnchor();
                yugetGITConfig.visualDiff.hudX = 8;
                yugetGITConfig.visualDiff.hudY = 8;
                refreshButtons();
                saveConfig();
                initGui();
                return;
            case 100:
                applyCoordinatesFromFields();
                saveConfig();
                mc.displayGuiScreen(parentScreen);
                return;
            default:
                break;
        }

        saveConfig();
        refreshButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, TextFormatting.WHITE + "Visual Diff HUD Placement", width / 2, 18, 0xFFFFFF);

        drawString(fontRenderer, TextFormatting.WHITE + "X", xField.x - 10, xField.y + 6, 0xFFFFFF);
        drawString(fontRenderer, TextFormatting.WHITE + "Y", yField.x - 10, yField.y + 6, 0xFFFFFF);
        xField.drawTextBox();
        yField.drawTextBox();

        int previewX = resolvePreviewX();
        int previewY = resolvePreviewY();
        int labelColor = 0xFFFFFF;
        drawString(fontRenderer, "Changes", previewX, previewY, labelColor);
        drawString(fontRenderer, "+12", previewX, previewY + 10, 0x55FF55);
        int nextX = previewX + fontRenderer.getStringWidth("+12") + 6;
        drawString(fontRenderer, "~5", nextX, previewY + 10, 0xFFFF55);
        nextX += fontRenderer.getStringWidth("~5") + 6;
        drawString(fontRenderer, "-3", nextX, previewY + 10, 0xFF5555);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (xField != null) {
            xField.updateCursorCounter();
        }
        if (yField != null) {
            yField.updateCursorCounter();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        boolean consumed = false;
        if (xField != null) {
            consumed |= xField.textboxKeyTyped(typedChar, keyCode);
        }
        if (yField != null) {
            consumed |= yField.textboxKeyTyped(typedChar, keyCode);
        }
        if (consumed) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (xField != null) {
            xField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (yField != null) {
            yField.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    private void refreshButtons() {
        if (locationButton != null) {
            locationButton.displayString = "Location: " + describePlacement();
        }
        if (xField != null && !xField.isFocused()) {
            xField.setText(String.valueOf(getHorizontalValue()));
        }
        if (yField != null && !yField.isFocused()) {
            yField.setText(String.valueOf(getVerticalValue()));
        }
    }

    private void cycleLocation() {
        if (yugetGITConfig.visualDiff.hudLeftX >= 0 && yugetGITConfig.visualDiff.hudTopY >= 0) {
            setTopRightAnchor();
            return;
        }
        if (yugetGITConfig.visualDiff.hudRightX >= 0 && yugetGITConfig.visualDiff.hudTopY >= 0) {
            setBottomRightAnchor();
            return;
        }
        if (yugetGITConfig.visualDiff.hudRightX >= 0 && yugetGITConfig.visualDiff.hudBottomY >= 0) {
            setBottomLeftAnchor();
            return;
        }
        setTopLeftAnchor();
    }

    private void applyCoordinatesFromFields() {
        int xValue = parseCoordinate(xField == null ? null : xField.getText(), getHorizontalValue());
        int yValue = parseCoordinate(yField == null ? null : yField.getText(), getVerticalValue());

        if (yugetGITConfig.visualDiff.hudLeftX >= 0) {
            yugetGITConfig.visualDiff.hudLeftX = xValue;
        } else if (yugetGITConfig.visualDiff.hudRightX >= 0) {
            yugetGITConfig.visualDiff.hudRightX = xValue;
        } else {
            yugetGITConfig.visualDiff.hudX = xValue;
        }

        if (yugetGITConfig.visualDiff.hudTopY >= 0) {
            yugetGITConfig.visualDiff.hudTopY = yValue;
        } else if (yugetGITConfig.visualDiff.hudBottomY >= 0) {
            yugetGITConfig.visualDiff.hudBottomY = yValue;
        } else {
            yugetGITConfig.visualDiff.hudY = yValue;
        }
    }

    private int parseCoordinate(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(trimmed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int getHorizontalValue() {
        if (yugetGITConfig.visualDiff.hudLeftX >= 0) {
            return yugetGITConfig.visualDiff.hudLeftX;
        }
        if (yugetGITConfig.visualDiff.hudRightX >= 0) {
            return yugetGITConfig.visualDiff.hudRightX;
        }
        return yugetGITConfig.visualDiff.hudX;
    }

    private int getVerticalValue() {
        if (yugetGITConfig.visualDiff.hudTopY >= 0) {
            return yugetGITConfig.visualDiff.hudTopY;
        }
        if (yugetGITConfig.visualDiff.hudBottomY >= 0) {
            return yugetGITConfig.visualDiff.hudBottomY;
        }
        return yugetGITConfig.visualDiff.hudY;
    }

    private void setTopLeftAnchor() {
        yugetGITConfig.visualDiff.hudLeftX = 8;
        yugetGITConfig.visualDiff.hudTopY = 8;
        yugetGITConfig.visualDiff.hudRightX = -1;
        yugetGITConfig.visualDiff.hudBottomY = -1;
    }

    private void setTopRightAnchor() {
        yugetGITConfig.visualDiff.hudRightX = 8;
        yugetGITConfig.visualDiff.hudTopY = 8;
        yugetGITConfig.visualDiff.hudLeftX = -1;
        yugetGITConfig.visualDiff.hudBottomY = -1;
    }

    private void setBottomLeftAnchor() {
        yugetGITConfig.visualDiff.hudLeftX = 8;
        yugetGITConfig.visualDiff.hudBottomY = 8;
        yugetGITConfig.visualDiff.hudRightX = -1;
        yugetGITConfig.visualDiff.hudTopY = -1;
    }

    private void setBottomRightAnchor() {
        yugetGITConfig.visualDiff.hudRightX = 8;
        yugetGITConfig.visualDiff.hudBottomY = 8;
        yugetGITConfig.visualDiff.hudLeftX = -1;
        yugetGITConfig.visualDiff.hudTopY = -1;
    }

    private int resolvePreviewX() {
        if (yugetGITConfig.visualDiff.hudLeftX >= 0) {
            return yugetGITConfig.visualDiff.hudLeftX;
        }
        if (yugetGITConfig.visualDiff.hudRightX >= 0) {
            int previewWidth = fontRenderer.getStringWidth("Changes");
            return Math.max(0, width - previewWidth - yugetGITConfig.visualDiff.hudRightX);
        }
        return Math.max(0, yugetGITConfig.visualDiff.hudX);
    }

    private int resolvePreviewY() {
        if (yugetGITConfig.visualDiff.hudTopY >= 0) {
            return yugetGITConfig.visualDiff.hudTopY;
        }
        if (yugetGITConfig.visualDiff.hudBottomY >= 0) {
            return Math.max(0, height - 20 - yugetGITConfig.visualDiff.hudBottomY);
        }
        return Math.max(0, yugetGITConfig.visualDiff.hudY);
    }

    private boolean isHudBottomRight() {
        return yugetGITConfig.visualDiff.hudRightX >= 0 && yugetGITConfig.visualDiff.hudBottomY >= 0;
    }

    private String describePlacement() {
        if (yugetGITConfig.visualDiff.hudLeftX >= 0 && yugetGITConfig.visualDiff.hudTopY >= 0) {
            return "top-left";
        }
        if (yugetGITConfig.visualDiff.hudRightX >= 0 && yugetGITConfig.visualDiff.hudTopY >= 0) {
            return "top-right";
        }
        if (yugetGITConfig.visualDiff.hudLeftX >= 0 && yugetGITConfig.visualDiff.hudBottomY >= 0) {
            return "bottom-left";
        }
        if (yugetGITConfig.visualDiff.hudRightX >= 0 && yugetGITConfig.visualDiff.hudBottomY >= 0) {
            return "bottom-right";
        }
        return "top-left";
    }

    private void saveConfig() {
        ConfigManager.sync("yugetgit", Config.Type.INSTANCE);
    }
}
