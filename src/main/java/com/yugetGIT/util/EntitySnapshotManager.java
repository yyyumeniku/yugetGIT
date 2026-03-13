package com.yugetGIT.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EntitySnapshotManager {

    private static final String SNAPSHOT_RELATIVE_PATH = "meta/entities.dat";
    private static final double POSITION_EPSILON = 0.25D;
    private static final float ROTATION_EPSILON = 2.0F;

    private EntitySnapshotManager() {
    }

    public static void capture(MinecraftServer server, File repoDir) throws Exception {
        File snapshotFile = getSnapshotFile(repoDir);
        File parent = snapshotFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Failed to create entity snapshot directory: " + parent.getAbsolutePath());
        }

        List<SnapshotRecord> records = new ArrayList<>();
        
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

                records.add(new SnapshotRecord(
                    entity.getUniqueID(),
                    dimension,
                    entity.posX,
                    entity.posY,
                    entity.posZ,
                    entity.rotationYaw,
                    entity.rotationPitch
                ));
            }
        }

        records.sort(Comparator.comparing(record -> record.uuid.toString()));
        for (SnapshotRecord record : records) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setUniqueId("uuid", record.uuid);
            tag.setInteger("dim", record.dimension);
            tag.setDouble("x", record.x);
            tag.setDouble("y", record.y);
            tag.setDouble("z", record.z);
            tag.setFloat("yaw", record.yaw);
            tag.setFloat("pitch", record.pitch);
            entities.appendTag(tag);
        }

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("entities", entities);
        root.setInteger("count", entities.tagCount());

        if (snapshotFile.exists()) {
            NBTTagCompound existing = CompressedStreamTools.read(snapshotFile);
            if (existing != null && isEquivalentSnapshot(existing, records)) {
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

    private static boolean isEquivalentSnapshot(NBTTagCompound existingRoot, List<SnapshotRecord> records) {
        NBTTagList existingEntities = existingRoot.getTagList("entities", 10);
        if (existingEntities.tagCount() != records.size()) {
            return false;
        }

        Map<UUID, SnapshotEntry> existingByUuid = new HashMap<>();
        for (int i = 0; i < existingEntities.tagCount(); i++) {
            NBTTagCompound tag = existingEntities.getCompoundTagAt(i);
            UUID uuid = tag.getUniqueId("uuid");
            existingByUuid.put(uuid, new SnapshotEntry(
                tag.getInteger("dim"),
                tag.getDouble("x"),
                tag.getDouble("y"),
                tag.getDouble("z"),
                tag.getFloat("yaw"),
                tag.getFloat("pitch")
            ));
        }

        for (SnapshotRecord record : records) {
            SnapshotEntry existing = existingByUuid.get(record.uuid);
            if (existing == null) {
                return false;
            }
            if (existing.dimension != record.dimension) {
                return false;
            }
            if (!isClose(existing.x, record.x, POSITION_EPSILON)
                || !isClose(existing.y, record.y, POSITION_EPSILON)
                || !isClose(existing.z, record.z, POSITION_EPSILON)) {
                return false;
            }
            if (!isCloseAngle(existing.yaw, record.yaw, ROTATION_EPSILON)
                || !isCloseAngle(existing.pitch, record.pitch, ROTATION_EPSILON)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isClose(double left, double right, double epsilon) {
        return Math.abs(left - right) <= epsilon;
    }

    private static boolean isCloseAngle(float left, float right, float epsilon) {
        float delta = Math.abs(left - right) % 360.0F;
        if (delta > 180.0F) {
            delta = 360.0F - delta;
        }
        return delta <= epsilon;
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

    private static final class SnapshotRecord {
        private final UUID uuid;
        private final int dimension;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;

        private SnapshotRecord(UUID uuid, int dimension, double x, double y, double z, float yaw, float pitch) {
            this.uuid = uuid;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
