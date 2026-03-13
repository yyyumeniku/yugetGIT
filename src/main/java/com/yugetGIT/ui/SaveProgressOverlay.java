package com.yugetGIT.ui;

import com.yugetGIT.util.SaveProgressTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class SaveProgressOverlay {

    private static final ResourceLocation GUI_BARS_TEXTURES = new ResourceLocation("textures/gui/bars.png");

    @SubscribeEvent
    public void onDrawGui(GuiScreenEvent.DrawScreenEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == null) {
            return;
        }

        drawProgressBar(mc);
    }

    private void drawProgressBar(Minecraft mc) {
        SaveProgressTracker.Snapshot snapshot = SaveProgressTracker.snapshot();
        if (!snapshot.isActive()) {
            return;
        }

        ScaledResolution scaled = new ScaledResolution(mc);
        int screenWidth = scaled.getScaledWidth();
        int barWidth = 182;
        int x = (screenWidth / 2) - (barWidth / 2);
        int y = 12;

        mc.getTextureManager().bindTexture(GUI_BARS_TEXTURES);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 30.0F, barWidth, 5, 256.0F, 256.0F);
        int filled = (int) (snapshot.getPercent() * 1.82F);
        if (filled > 0) {
            Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 35.0F, filled, 5, 256.0F, 256.0F);
        }
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 110.0F, barWidth, 5, 256.0F, 256.0F);
        if (filled > 0) {
            Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 115.0F, filled, 5, 256.0F, 256.0F);
        }

        double mb = snapshot.getBytesWritten() / (1024.0 * 1024.0);
        String title = String.format(
            "%s%s %d%% | chunks %d | %.2f MB",
            TextFormatting.LIGHT_PURPLE,
            snapshot.getStage(),
            snapshot.getPercent(),
            snapshot.getChangedChunks(),
            mb
        );
        int textX = (screenWidth / 2) - (mc.fontRenderer.getStringWidth(title) / 2);
        mc.fontRenderer.drawStringWithShadow(title, textX, y - 10, 0xFFFFFF);

        String detail = snapshot.getDetail();
        if (detail != null && !detail.isEmpty()) {
            int detailX = (screenWidth / 2) - (mc.fontRenderer.getStringWidth(detail) / 2);
            mc.fontRenderer.drawStringWithShadow(TextFormatting.GRAY + detail, detailX, y + 8, 0xFFFFFF);
        }
    }
}
