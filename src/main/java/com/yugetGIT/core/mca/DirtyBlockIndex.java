package com.yugetGIT.core.mca;

import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DirtyBlockIndex {

    public enum ChangeType {
        ADDED,
        MODIFIED,
        REMOVED
    }

    public static final class DirtyBlockChange {
        private final ChangeType type;
        private final int tintRgb;

        public DirtyBlockChange(ChangeType type, int tintRgb) {
            this.type = type;
            this.tintRgb = tintRgb;
        }

        public ChangeType getType() {
            return type;
        }

        public int getTintRgb() {
            return tintRgb;
        }
    }

    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, DirtyBlockChange>> DIRTY_BLOCKS_BY_WORLD = new ConcurrentHashMap<>();

    private DirtyBlockIndex() {
    }

    public static void markAdded(String worldKey, BlockPos pos, int tintRgb) {
        markChange(worldKey, pos, ChangeType.ADDED, tintRgb);
    }

    public static void markRemoved(String worldKey, BlockPos pos, int tintRgb) {
        markChange(worldKey, pos, ChangeType.REMOVED, tintRgb);
    }

    public static void markModified(String worldKey, BlockPos pos, int tintRgb) {
        markChange(worldKey, pos, ChangeType.MODIFIED, tintRgb);
    }

    public static Map<Long, DirtyBlockChange> snapshotWorld(String worldKey) {
        if (worldKey == null || worldKey.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, DirtyBlockChange> worldMap = DIRTY_BLOCKS_BY_WORLD.get(worldKey.trim());
        if (worldMap == null || worldMap.isEmpty()) {
            return Collections.emptyMap();
        }

        return new HashMap<>(worldMap);
    }

    public static void clearWorld(String worldKey) {
        if (worldKey == null || worldKey.trim().isEmpty()) {
            return;
        }

        DIRTY_BLOCKS_BY_WORLD.remove(worldKey.trim());
    }

    private static void markChange(String worldKey, BlockPos pos, ChangeType incomingType, int tintRgb) {
        if (worldKey == null || worldKey.trim().isEmpty() || pos == null || incomingType == null) {
            return;
        }

        String normalizedWorldKey = worldKey.trim();
        ConcurrentHashMap<Long, DirtyBlockChange> worldMap = DIRTY_BLOCKS_BY_WORLD.computeIfAbsent(
            normalizedWorldKey,
            ignored -> new ConcurrentHashMap<>()
        );

        long packedPos = pos.toLong();
        worldMap.compute(packedPos, (ignored, existingChange) -> merge(existingChange, incomingType, tintRgb));
    }

    private static DirtyBlockChange merge(DirtyBlockChange existingChange, ChangeType incomingType, int tintRgb) {
        if (existingChange == null) {
            return new DirtyBlockChange(incomingType, tintRgb);
        }

        ChangeType existingType = existingChange.getType();
        if (existingType == ChangeType.ADDED && incomingType == ChangeType.REMOVED) {
            return null;
        }
        if (existingType == ChangeType.REMOVED && incomingType == ChangeType.ADDED) {
            return null;
        }

        if (existingType == ChangeType.ADDED) {
            return new DirtyBlockChange(ChangeType.ADDED, tintRgb);
        }
        if (existingType == ChangeType.REMOVED) {
            return new DirtyBlockChange(ChangeType.REMOVED, existingChange.getTintRgb());
        }

        ChangeType resolvedType = incomingType == ChangeType.MODIFIED ? ChangeType.MODIFIED : incomingType;
        return new DirtyBlockChange(resolvedType, tintRgb);
    }
}
