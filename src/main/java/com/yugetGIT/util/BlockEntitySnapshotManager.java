package com.yugetGIT.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BlockEntitySnapshotManager {

    private static final String SNAPSHOT_RELATIVE_PATH = "meta/block_entities.dat";
    private static final Set<String> VOLATILE_NBT_KEYS = new HashSet<>();

    static {
        VOLATILE_NBT_KEYS.add("BurnTime");
        VOLATILE_NBT_KEYS.add("CookTime");
        VOLATILE_NBT_KEYS.add("CookTimeTotal");
        VOLATILE_NBT_KEYS.add("TransferCooldown");
        VOLATILE_NBT_KEYS.add("Age");
        VOLATILE_NBT_KEYS.add("Delay");
        VOLATILE_NBT_KEYS.add("Ticks");
        VOLATILE_NBT_KEYS.add("TickCount");
        VOLATILE_NBT_KEYS.add("LastExecution");
        VOLATILE_NBT_KEYS.add("SuccessCount");
    }

    private BlockEntitySnapshotManager() {
    }

    public static void capture(MinecraftServer server, File repoDir) throws Exception {
        File snapshotFile = getSnapshotFile(repoDir);
        File parent = snapshotFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Failed to create block-entity snapshot directory: " + parent.getAbsolutePath());
        }

        List<BlockEntityRecord> records = new ArrayList<>();
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

                NBTTagCompound data = tileEntity.writeToNBT(new NBTTagCompound());
                records.add(new BlockEntityRecord(dimension, data.getInteger("x"), data.getInteger("y"), data.getInteger("z"), data));
            }
        }

        records.sort(Comparator
            .comparingInt((BlockEntityRecord record) -> record.dimension)
            .thenComparingInt(record -> record.x)
            .thenComparingInt(record -> record.y)
            .thenComparingInt(record -> record.z));

        for (BlockEntityRecord record : records) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("dim", record.dimension);
            entry.setTag("data", record.data);
            blockEntities.appendTag(entry);
        }

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("block_entities", blockEntities);
        root.setInteger("count", blockEntities.tagCount());

        if (snapshotFile.exists()) {
            NBTTagCompound existing = CompressedStreamTools.read(snapshotFile);
            if (existing != null && isEquivalentSnapshot(existing, root)) {
                return;
            }
        }

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

    private static boolean isEquivalentSnapshot(NBTTagCompound left, NBTTagCompound right) {
        NBTTagCompound leftNormalized = normalizeForComparison(left);
        NBTTagCompound rightNormalized = normalizeForComparison(right);
        return leftNormalized.equals(rightNormalized);
    }

    private static NBTTagCompound normalizeForComparison(NBTTagCompound source) {
        NBTTagCompound copy = source.copy();
        stripVolatileKeys(copy);
        return copy;
    }

    private static void stripVolatileKeys(NBTTagCompound compound) {
        List<String> keys = new ArrayList<>(compound.getKeySet());
        for (String key : keys) {
            if (VOLATILE_NBT_KEYS.contains(key)) {
                compound.removeTag(key);
                continue;
            }

            NBTBase child = compound.getTag(key);
            if (child instanceof NBTTagCompound) {
                stripVolatileKeys((NBTTagCompound) child);
            } else if (child instanceof NBTTagList) {
                stripVolatileKeys((NBTTagList) child);
            }
        }
    }

    private static void stripVolatileKeys(NBTTagList list) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTBase child = list.get(i);
            if (child instanceof NBTTagCompound) {
                stripVolatileKeys((NBTTagCompound) child);
            } else if (child instanceof NBTTagList) {
                stripVolatileKeys((NBTTagList) child);
            }
        }
    }

    private static final class BlockEntityRecord {
        private final int dimension;
        private final int x;
        private final int y;
        private final int z;
        private final NBTTagCompound data;

        private BlockEntityRecord(int dimension, int x, int y, int z, NBTTagCompound data) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
        }
    }
}
