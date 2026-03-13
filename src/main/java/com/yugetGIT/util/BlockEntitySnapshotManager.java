package com.yugetGIT.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.io.File;

public final class BlockEntitySnapshotManager {

    private static final String SNAPSHOT_RELATIVE_PATH = "meta/block_entities.dat";

    private BlockEntitySnapshotManager() {
    }

    public static void capture(MinecraftServer server, File repoDir) throws Exception {
        File snapshotFile = getSnapshotFile(repoDir);
        File parent = snapshotFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Failed to create block-entity snapshot directory: " + parent.getAbsolutePath());
        }

        NBTTagList blockEntities = new NBTTagList();
        for (WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }

            int dimension = world.provider.getDimension();
            for (TileEntity tileEntity : world.loadedTileEntityList) {
                if (tileEntity == null || tileEntity.isInvalid()) {
                    continue;
                }

                NBTTagCompound entry = new NBTTagCompound();
                entry.setInteger("dim", dimension);
                entry.setTag("data", tileEntity.writeToNBT(new NBTTagCompound()));
                blockEntities.appendTag(entry);
            }
        }

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("block_entities", blockEntities);
        root.setInteger("count", blockEntities.tagCount());
        CompressedStreamTools.write(root, snapshotFile);
    }

    public static int apply(MinecraftServer server, File repoDir) throws Exception {
        File snapshotFile = getSnapshotFile(repoDir);
        if (!snapshotFile.exists()) {
            return 0;
        }

        NBTTagCompound root = CompressedStreamTools.read(snapshotFile);
        if (root == null) {
            return 0;
        }

        NBTTagList blockEntities = root.getTagList("block_entities", 10);
        int refreshed = 0;
        for (int i = 0; i < blockEntities.tagCount(); i++) {
            NBTTagCompound entry = blockEntities.getCompoundTagAt(i);
            int dimension = entry.getInteger("dim");
            WorldServer world = server.getWorld(dimension);
            if (world == null) {
                continue;
            }

            NBTTagCompound data = entry.getCompoundTag("data");
            BlockPos position = new BlockPos(data.getInteger("x"), data.getInteger("y"), data.getInteger("z"));

            world.getChunk(position);
            TileEntity tileEntity = world.getTileEntity(position);
            if (tileEntity == null) {
                continue;
            }

            tileEntity.readFromNBT(data);
            tileEntity.markDirty();
            IBlockState state = world.getBlockState(position);
            world.notifyBlockUpdate(position, state, state, 3);
            refreshed++;
        }

        return refreshed;
    }

    private static File getSnapshotFile(File repoDir) {
        return new File(repoDir, SNAPSHOT_RELATIVE_PATH);
    }
}
