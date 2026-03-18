package com.yugetGIT.events;

import com.yugetGIT.config.yugetGITConfig;
import com.yugetGIT.core.mca.DirtyBlockIndex;
import com.yugetGIT.core.mca.DirtyChunkIndex;
import com.yugetGIT.util.SaveEventGuard;
import net.minecraft.block.Block;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockPressurePlate;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.block.material.MapColor;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class ChunkDirtyTrackerHandler {

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event == null) {
            return;
        }
        markDirtyIfApplicable(event.getWorld(), event.getPos(), event.getState(), DirtyBlockIndex.ChangeType.REMOVED, true);
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        if (event == null) {
            return;
        }
        markDirtyIfApplicable(event.getWorld(), event.getPos(), event.getPlacedBlock(), DirtyBlockIndex.ChangeType.ADDED, true);
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
            markDirtyIfApplicable(event.getWorld(), snapshot.getPos(), event.getPlacedBlock(), DirtyBlockIndex.ChangeType.ADDED, true);
        }
    }

    @SubscribeEvent
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (event == null) {
            return;
        }
        markDirtyIfApplicable(event.getWorld(), event.getPos(), event.getNewState(), DirtyBlockIndex.ChangeType.MODIFIED, false);
    }

    @SubscribeEvent
    public void onCropGrow(BlockEvent.CropGrowEvent.Post event) {
        if (event == null) {
            return;
        }

        if (!yugetGITConfig.backup.trackCropGrowthChanges) {
            return;
        }

        markDirtyIfApplicable(event.getWorld(), event.getPos(), event.getState(), DirtyBlockIndex.ChangeType.MODIFIED, false);
    }

    private void markDirtyIfApplicable(World world,
                                       BlockPos pos,
                                       IBlockState state,
                                       DirtyBlockIndex.ChangeType changeType,
                                       boolean trackVisualDiff) {
        Block block = state == null ? null : state.getBlock();
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

        if (trackVisualDiff) {
            int tintRgb = resolveTintRgb(state, worldServer, pos);
            if (changeType == DirtyBlockIndex.ChangeType.ADDED) {
                DirtyBlockIndex.markAdded(worldKey, pos, tintRgb);
            } else if (changeType == DirtyBlockIndex.ChangeType.REMOVED) {
                DirtyBlockIndex.markRemoved(worldKey, pos, tintRgb);
            } else {
                DirtyBlockIndex.markModified(worldKey, pos, tintRgb);
            }
        }
    }

    private int resolveTintRgb(IBlockState state, World world, BlockPos pos) {
        if (state == null || state.getBlock() == null) {
            return 0xFFFFFF;
        }

        try {
            MapColor mapColor = state.getBlock().getMapColor(state, world, pos);
            if (mapColor != null && mapColor.colorValue != 0) {
                return mapColor.colorValue;
            }
        } catch (Exception ignored) {
        }
        return 0xFFFFFF;
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