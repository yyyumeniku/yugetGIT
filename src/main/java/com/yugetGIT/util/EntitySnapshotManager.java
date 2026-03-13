package com.yugetGIT.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EntitySnapshotManager {

    private static final String SNAPSHOT_RELATIVE_PATH = "meta/entities.dat";

    private EntitySnapshotManager() {
    }

    public static void capture(MinecraftServer server, File repoDir) throws Exception {
        File snapshotFile = getSnapshotFile(repoDir);
        File parent = snapshotFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Failed to create entity snapshot directory: " + parent.getAbsolutePath());
        }

        NBTTagList entities = new NBTTagList();
        for (WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }

            int dimension = world.provider.getDimension();
            for (Entity entity : world.loadedEntityList) {
                if (entity == null || entity.isDead || entity instanceof EntityPlayer) {
                    continue;
                }

                NBTTagCompound tag = new NBTTagCompound();
                tag.setUniqueId("uuid", entity.getUniqueID());
                tag.setInteger("dim", dimension);
                tag.setDouble("x", entity.posX);
                tag.setDouble("y", entity.posY);
                tag.setDouble("z", entity.posZ);
                tag.setFloat("yaw", entity.rotationYaw);
                tag.setFloat("pitch", entity.rotationPitch);
                entities.appendTag(tag);
            }
        }

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("entities", entities);
        root.setInteger("count", entities.tagCount());
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

        Map<UUID, SnapshotEntry> snapshots = new HashMap<>();
        NBTTagList entities = root.getTagList("entities", 10);
        for (int i = 0; i < entities.tagCount(); i++) {
            NBTTagCompound tag = entities.getCompoundTagAt(i);
            UUID uuid = tag.getUniqueId("uuid");
            snapshots.put(uuid, new SnapshotEntry(
                tag.getInteger("dim"),
                tag.getDouble("x"),
                tag.getDouble("y"),
                tag.getDouble("z"),
                tag.getFloat("yaw"),
                tag.getFloat("pitch")
            ));
        }

        int restored = 0;
        for (WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }

            int dimension = world.provider.getDimension();
            for (Entity entity : world.loadedEntityList) {
                if (entity == null || entity.isDead || entity instanceof EntityPlayer) {
                    continue;
                }

                SnapshotEntry snapshot = snapshots.get(entity.getUniqueID());
                if (snapshot == null || snapshot.dimension != dimension) {
                    continue;
                }

                entity.setPositionAndRotation(snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
                entity.motionX = 0.0D;
                entity.motionY = 0.0D;
                entity.motionZ = 0.0D;
                entity.velocityChanged = true;
                restored++;
            }
        }

        return restored;
    }

    private static File getSnapshotFile(File repoDir) {
        return new File(repoDir, SNAPSHOT_RELATIVE_PATH);
    }

    private static final class SnapshotEntry {
        private final int dimension;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;

        private SnapshotEntry(int dimension, double x, double y, double z, float yaw, float pitch) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
