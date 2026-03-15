package com.yugetGIT.core.mca;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DirtyChunkIndex {

    private static final ConcurrentHashMap<String, Set<ChunkKey>> DIRTY_CHUNKS_BY_WORLD = new ConcurrentHashMap<>();

    private DirtyChunkIndex() {
    }

    public static void markChunkDirty(String worldKey, int dimensionId, int chunkX, int chunkZ) {
        if (worldKey == null || worldKey.trim().isEmpty()) {
            return;
        }

        String normalizedWorldKey = worldKey.trim();
        Set<ChunkKey> worldSet = DIRTY_CHUNKS_BY_WORLD.computeIfAbsent(
            normalizedWorldKey,
            ignored -> ConcurrentHashMap.newKeySet()
        );
        worldSet.add(new ChunkKey(dimensionId, chunkX, chunkZ));
    }

    public static Set<ChunkKey> snapshotWorld(String worldKey) {
        if (worldKey == null || worldKey.trim().isEmpty()) {
            return Collections.emptySet();
        }

        Set<ChunkKey> worldSet = DIRTY_CHUNKS_BY_WORLD.get(worldKey.trim());
        if (worldSet == null || worldSet.isEmpty()) {
            return Collections.emptySet();
        }

        return new HashSet<>(worldSet);
    }

    public static void clearWorld(String worldKey) {
        if (worldKey == null || worldKey.trim().isEmpty()) {
            return;
        }

        DIRTY_CHUNKS_BY_WORLD.remove(worldKey.trim());
    }

    public static final class ChunkKey {
        private final int dimensionId;
        private final int chunkX;
        private final int chunkZ;

        public ChunkKey(int dimensionId, int chunkX, int chunkZ) {
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public int getDimensionId() {
            return dimensionId;
        }

        public int getChunkX() {
            return chunkX;
        }

        public int getChunkZ() {
            return chunkZ;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ChunkKey)) {
                return false;
            }
            ChunkKey other = (ChunkKey) obj;
            return dimensionId == other.dimensionId
                && chunkX == other.chunkX
                && chunkZ == other.chunkZ;
        }

        @Override
        public int hashCode() {
            int result = dimensionId;
            result = 31 * result + chunkX;
            result = 31 * result + chunkZ;
            return result;
        }
    }
}