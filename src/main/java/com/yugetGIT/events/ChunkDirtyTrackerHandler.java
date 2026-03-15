package com.yugetGIT.events;

import com.yugetGIT.config.yugetGITConfig;
import com.yugetGIT.core.mca.DirtyChunkIndex;
import com.yugetGIT.util.SaveEventGuard;
import net.minecraft.block.Block;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockPressurePlate;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class ChunkDirtyTrackerHandler {

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event == null) {
            return;
        }
        markDirtyIfApplicable(event.getWorld(), event.getPos(), event.getState() == null ? null : event.getState().getBlock());
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        if (event == null) {
            return;
        }
        markDirtyIfApplicable(event.getWorld(), event.getPos(), event.getPlacedBlock() == null ? null : event.getPlacedBlock().getBlock());
    }

    @SubscribeEvent
    public void onMultiBlockPlace(BlockEvent.MultiPlaceEvent event) {
        if (event == null || event.getReplacedBlockSnapshots() == null) {
            return;
        }

        for (net.minecraftforge.common.util.BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            if (snapshot == null || snapshot.getPos() == null) {
                continue;
            }
            markDirtyIfApplicable(event.getWorld(), snapshot.getPos(), event.getPlacedBlock() == null ? null : event.getPlacedBlock().getBlock());
        }
    }

    @SubscribeEvent
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (event == null) {
            return;
        }
        markDirtyIfApplicable(event.getWorld(), event.getPos(), event.getNewState() == null ? null : event.getNewState().getBlock());
    }

    @SubscribeEvent
    public void onCropGrow(BlockEvent.CropGrowEvent.Post event) {
        if (event == null) {
            return;
        }

        if (!yugetGITConfig.backup.trackCropGrowthChanges) {
            return;
        }

        markDirtyIfApplicable(event.getWorld(), event.getPos(), event.getState() == null ? null : event.getState().getBlock());
    }

    private void markDirtyIfApplicable(World world, BlockPos pos, Block block) {
        if (!(world instanceof WorldServer) || world.isRemote || pos == null) {
            return;
        }
        if (SaveEventGuard.isSuppressed()) {
            return;
        }
        if (block != null && shouldIgnoreInteractiveStateNoise(block)) {
            return;
        }

        WorldServer worldServer = (WorldServer) world;
        String worldKey = worldServer.getSaveHandler().getWorldDirectory().getName();
        int dimensionId = worldServer.provider.getDimension();
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        DirtyChunkIndex.markChunkDirty(worldKey, dimensionId, chunkX, chunkZ);
    }

    private boolean shouldIgnoreInteractiveStateNoise(Block block) {
        if (!yugetGITConfig.backup.ignoreInteractiveStateToggles) {
            return false;
        }

        return block instanceof BlockDoor
            || block instanceof BlockTrapDoor
            || block instanceof BlockButton
            || block instanceof BlockPressurePlate
            || block instanceof BlockFenceGate;
    }
}