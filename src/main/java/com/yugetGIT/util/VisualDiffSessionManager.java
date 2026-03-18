package com.yugetGIT.util;

import com.yugetGIT.config.yugetGITConfig;
import com.yugetGIT.core.mca.DirtyBlockIndex;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VisualDiffSessionManager {

    public enum Mode {
        OFF,
        DIFF
    }

    public static final class DiffSnapshot {

        public static final class ChangedBlock {
            private final int x;
            private final int y;
            private final int z;
            private final DirtyBlockIndex.ChangeType type;
            private final int tintRgb;

            public ChangedBlock(int x, int y, int z, DirtyBlockIndex.ChangeType type, int tintRgb) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.type = type;
                this.tintRgb = tintRgb;
            }

            public int getX() {
                return x;
            }

            public int getY() {
                return y;
            }

            public int getZ() {
                return z;
            }

            public DirtyBlockIndex.ChangeType getType() {
                return type;
            }

            public int getTintRgb() {
                return tintRgb;
            }
        }

        private final int addedCount;
        private final int modifiedCount;
        private final int removedCount;
        private final boolean truncated;
        private final List<ChangedBlock> changedBlocks;
        private final long computedAtEpochMs;

        public DiffSnapshot(int addedCount,
                            int modifiedCount,
                            int removedCount,
                            boolean truncated,
                            List<ChangedBlock> changedBlocks,
                            long computedAtEpochMs) {
            this.addedCount = addedCount;
            this.modifiedCount = modifiedCount;
            this.removedCount = removedCount;
            this.truncated = truncated;
            this.changedBlocks = changedBlocks == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(changedBlocks));
            this.computedAtEpochMs = computedAtEpochMs;
        }

        public int getAddedCount() {
            return addedCount;
        }

        public int getModifiedCount() {
            return modifiedCount;
        }

        public int getRemovedCount() {
            return removedCount;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public List<ChangedBlock> getChangedBlocks() {
            return changedBlocks;
        }

        public long getComputedAtEpochMs() {
            return computedAtEpochMs;
        }

        public int getTotalCount() {
            return addedCount + modifiedCount + removedCount;
        }
    }

    private static final class SessionState {
        private Mode mode = Mode.OFF;
        private DiffSnapshot snapshot;
    }

    private static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();

    private VisualDiffSessionManager() {
    }

    public static Mode getMode(String worldKey) {
        return getOrCreate(worldKey).mode;
    }

    public static DiffSnapshot getSnapshot(String worldKey) {
        return getOrCreate(worldKey).snapshot;
    }

    public static void enable(String worldKey, DiffSnapshot snapshot) {
        SessionState state = getOrCreate(worldKey);
        state.mode = Mode.DIFF;
        state.snapshot = snapshot;
    }

    public static void disable(String worldKey) {
        SessionState state = getOrCreate(worldKey);
        state.mode = Mode.OFF;
        state.snapshot = null;
    }

    public static DiffSnapshot computeSnapshot(String worldKey,
                                               Integer centerX,
                                               Integer centerY,
                                               Integer centerZ,
                                               int radiusBlocks,
                                               int maxBlocks) {
        Map<Long, DirtyBlockIndex.DirtyBlockChange> dirtyBlocks = DirtyBlockIndex.snapshotWorld(worldKey);
        if (dirtyBlocks.isEmpty()) {
            return new DiffSnapshot(0, 0, 0, false, Collections.emptyList(), System.currentTimeMillis());
        }

        int cappedMaxBlocks = Math.max(64, maxBlocks);
        int radius = Math.max(16, radiusBlocks);
        int radiusSquared = radius * radius;

        int addedCount = 0;
        int modifiedCount = 0;
        int removedCount = 0;
        boolean truncated = false;
        boolean showModified = yugetGITConfig.visualDiff.showModified;
        List<DiffSnapshot.ChangedBlock> rendered = new ArrayList<>();

        for (Map.Entry<Long, DirtyBlockIndex.DirtyBlockChange> entry : dirtyBlocks.entrySet()) {
            BlockPos pos = BlockPos.fromLong(entry.getKey());
            if (centerX != null && centerY != null && centerZ != null) {
                int dx = pos.getX() - centerX;
                int dy = pos.getY() - centerY;
                int dz = pos.getZ() - centerZ;
                if ((dx * dx + dy * dy + dz * dz) > radiusSquared) {
                    continue;
                }
            }

            DirtyBlockIndex.DirtyBlockChange blockChange = entry.getValue();
            DirtyBlockIndex.ChangeType type = blockChange.getType();
            if (type == DirtyBlockIndex.ChangeType.ADDED) {
                addedCount++;
            } else if (type == DirtyBlockIndex.ChangeType.REMOVED) {
                removedCount++;
            } else {
                if (!showModified) {
                    continue;
                }
                modifiedCount++;
            }

            if (rendered.size() < cappedMaxBlocks) {
                rendered.add(new DiffSnapshot.ChangedBlock(pos.getX(), pos.getY(), pos.getZ(), type, blockChange.getTintRgb()));
            } else {
                truncated = true;
            }
        }

        return new DiffSnapshot(
            addedCount,
            modifiedCount,
            removedCount,
            truncated,
            rendered,
            System.currentTimeMillis()
        );
    }

    private static SessionState getOrCreate(String worldKey) {
        String safeKey = worldKey == null ? "default" : worldKey;
        return SESSIONS.computeIfAbsent(safeKey, ignored -> new SessionState());
    }
}
