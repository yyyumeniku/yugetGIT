package com.yugetGIT.ui;

import com.yugetGIT.config.yugetGITConfig;
import com.yugetGIT.core.mca.DirtyBlockIndex;
import com.yugetGIT.util.VisualDiffSessionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class VisualDiffOverlay {

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!yugetGITConfig.visualDiff.enabled || !yugetGITConfig.visualDiff.hudEnabled) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.world == null || minecraft.player == null || minecraft.fontRenderer == null) {
            return;
        }

        String worldKey = resolveWorldKey();
        if (worldKey == null) {
            return;
        }

        VisualDiffSessionManager.Mode mode = VisualDiffSessionManager.getMode(worldKey);
        VisualDiffSessionManager.DiffSnapshot snapshot = VisualDiffSessionManager.getSnapshot(worldKey);
        if (mode != VisualDiffSessionManager.Mode.DIFF || snapshot == null) {
            return;
        }

        if (yugetGITConfig.visualDiff.autoRefresh) {
            snapshot = refreshSnapshot(minecraft, worldKey);
        }

        ScaledResolution resolution = event.getResolution();
        int x = resolveHudX(resolution, snapshot);
        int y = resolveHudY(resolution);
        int clampedHudWidth = Math.max(170, estimateHudWidth(snapshot));
        int maxX = Math.max(0, resolution.getScaledWidth() - clampedHudWidth);
        int maxY = Math.max(0, resolution.getScaledHeight() - 20);
        if (x > maxX) {
            x = maxX;
        }
        if (y > maxY) {
            y = maxY;
        }

        minecraft.fontRenderer.drawStringWithShadow(TextFormatting.WHITE + "Changes", x, y, 0xFFFFFF);
        int lineY = y + 10;
        int cursorX = x;

        String added = "+" + snapshot.getAddedCount();
        String modified = "~" + snapshot.getModifiedCount();
        String removed = "-" + snapshot.getRemovedCount();

        minecraft.fontRenderer.drawStringWithShadow(added, cursorX, lineY, 0x55FF55);
        cursorX += minecraft.fontRenderer.getStringWidth(added) + 6;
        minecraft.fontRenderer.drawStringWithShadow(modified, cursorX, lineY, 0xFFFF55);
        cursorX += minecraft.fontRenderer.getStringWidth(modified) + 6;
        minecraft.fontRenderer.drawStringWithShadow(removed, cursorX, lineY, 0xFF5555);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!yugetGITConfig.visualDiff.enabled) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        if (minecraft == null || minecraft.world == null || player == null) {
            return;
        }

        String worldKey = resolveWorldKey();
        if (worldKey == null) {
            return;
        }

        VisualDiffSessionManager.Mode mode = VisualDiffSessionManager.getMode(worldKey);
        VisualDiffSessionManager.DiffSnapshot snapshot = VisualDiffSessionManager.getSnapshot(worldKey);
        if (mode != VisualDiffSessionManager.Mode.DIFF || snapshot == null || snapshot.getChangedBlocks().isEmpty()) {
            return;
        }

        if (yugetGITConfig.visualDiff.autoRefresh) {
            snapshot = refreshSnapshot(minecraft, worldKey);
            if (snapshot == null || snapshot.getChangedBlocks().isEmpty()) {
                return;
            }
        }

        double cameraX = player.lastTickPosX + (player.posX - player.lastTickPosX) * event.getPartialTicks();
        double cameraY = player.lastTickPosY + (player.posY - player.lastTickPosY) * event.getPartialTicks();
        double cameraZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * event.getPartialTicks();

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO
        );
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GlStateManager.glLineWidth(1.5F);

        try {
            for (VisualDiffSessionManager.DiffSnapshot.ChangedBlock changedBlock : snapshot.getChangedBlocks()) {
                float[] color = resolveColor(changedBlock);
                double minX = changedBlock.getX() - cameraX;
                double minY = changedBlock.getY() - cameraY;
                double minZ = changedBlock.getZ() - cameraZ;

                double expand = 0.002D;
                AxisAlignedBB marker = new AxisAlignedBB(
                    minX - expand,
                    minY - expand,
                    minZ - expand,
                    minX + 1.0D + expand,
                    minY + 1.0D + expand,
                    minZ + 1.0D + expand
                );

                RenderGlobal.renderFilledBox(marker, color[0], color[1], color[2], 0.20F);
                RenderGlobal.drawSelectionBoundingBox(marker, color[0], color[1], color[2], 0.90F);
            }
        } finally {
            GlStateManager.enableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }
    }

    private int resolveHudX(ScaledResolution resolution, VisualDiffSessionManager.DiffSnapshot snapshot) {
        int width = resolution.getScaledWidth();
        int minimumWidth = Math.max(80, estimateHudWidth(snapshot));

        if (yugetGITConfig.visualDiff.hudLeftX >= 0) {
            return Math.max(0, yugetGITConfig.visualDiff.hudLeftX);
        }

        if (yugetGITConfig.visualDiff.hudRightX >= 0) {
            return Math.max(0, width - minimumWidth - yugetGITConfig.visualDiff.hudRightX);
        }

        return Math.max(0, yugetGITConfig.visualDiff.hudX);
    }

    private int resolveHudY(ScaledResolution resolution) {
        int height = resolution.getScaledHeight();

        if (yugetGITConfig.visualDiff.hudTopY >= 0) {
            return Math.max(0, yugetGITConfig.visualDiff.hudTopY);
        }

        if (yugetGITConfig.visualDiff.hudBottomY >= 0) {
            return Math.max(0, height - 20 - yugetGITConfig.visualDiff.hudBottomY);
        }

        return Math.max(0, yugetGITConfig.visualDiff.hudY);
    }

    private int estimateHudWidth(VisualDiffSessionManager.DiffSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.fontRenderer == null) {
            return 120;
        }
        String title = "Changes";
        String counts = "+" + snapshot.getAddedCount() + " ~" + snapshot.getModifiedCount() + " -" + snapshot.getRemovedCount();
        return Math.max(minecraft.fontRenderer.getStringWidth(title), minecraft.fontRenderer.getStringWidth(counts));
    }

    private VisualDiffSessionManager.DiffSnapshot refreshSnapshot(Minecraft minecraft, String worldKey) {
        Integer centerX = (int) Math.floor(minecraft.player.posX);
        Integer centerY = (int) Math.floor(minecraft.player.posY);
        Integer centerZ = (int) Math.floor(minecraft.player.posZ);
        VisualDiffSessionManager.DiffSnapshot snapshot = VisualDiffSessionManager.computeSnapshot(
            worldKey,
            centerX,
            centerY,
            centerZ,
            yugetGITConfig.visualDiff.maxOverlayDistanceBlocks,
            yugetGITConfig.visualDiff.maxOverlayBlocks
        );
        VisualDiffSessionManager.enable(worldKey, snapshot);
        return snapshot;
    }

    private float[] resolveColor(VisualDiffSessionManager.DiffSnapshot.ChangedBlock changedBlock) {
        if (changedBlock.getType() == DirtyBlockIndex.ChangeType.ADDED) {
            return new float[] {0.10F, 0.95F, 0.10F};
        }
        if (changedBlock.getType() == DirtyBlockIndex.ChangeType.REMOVED) {
            return new float[] {0.95F, 0.10F, 0.10F};
        }
        return new float[] {0.95F, 0.82F, 0.18F};
    }

    private String resolveWorldKey() {
        try {
            MinecraftServer server = Minecraft.getMinecraft().getIntegratedServer();
            if (server != null && server.getEntityWorld() != null && server.getEntityWorld().getSaveHandler() != null
                && server.getEntityWorld().getSaveHandler().getWorldDirectory() != null) {
                return server.getEntityWorld().getSaveHandler().getWorldDirectory().getName();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
