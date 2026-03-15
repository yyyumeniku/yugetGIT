package com.yugetGIT.core.mca;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WorldSnapshotStager {

    public interface StageProgressListener {
        void onProgress(StageProgress update);
    }

    public static final class StageProgress {
        private final int percent;
        private final int changedRegions;
        private final int changedChunks;
        private final int changedAuxiliaryEntries;
        private final long bytesWritten;

        public StageProgress(int percent, int changedRegions, int changedChunks, int changedAuxiliaryEntries, long bytesWritten) {
            this.percent = percent;
            this.changedRegions = changedRegions;
            this.changedChunks = changedChunks;
            this.changedAuxiliaryEntries = changedAuxiliaryEntries;
            this.bytesWritten = bytesWritten;
        }

        public int getPercent() {
            return percent;
        }

        public int getChangedRegions() {
            return changedRegions;
        }

        public int getChangedChunks() {
            return changedChunks;
        }

        public int getChangedAuxiliaryEntries() {
            return changedAuxiliaryEntries;
        }

        public long getBytesWritten() {
            return bytesWritten;
        }
    }

    public static final class StageResult {
        private final int changedRegions;
        private final int changedChunks;
        private final int changedAuxiliaryEntries;
        private final long bytesWritten;

        public StageResult(int changedRegions, int changedChunks, int changedAuxiliaryEntries, long bytesWritten) {
            this.changedRegions = changedRegions;
            this.changedChunks = changedChunks;
            this.changedAuxiliaryEntries = changedAuxiliaryEntries;
            this.bytesWritten = bytesWritten;
        }

        public int getChangedRegions() {
            return changedRegions;
        }

        public int getChangedChunks() {
            return changedChunks;
        }

        public int getChangedAuxiliaryEntries() {
            return changedAuxiliaryEntries;
        }

        public long getBytesWritten() {
            return bytesWritten;
        }

        public boolean hasChanges() {
            return changedChunks > 0 || changedAuxiliaryEntries > 0;
        }
    }

    public StageResult stageWorld(File worldDir, File repoDir) throws IOException {
        return stageWorld(worldDir, repoDir, null);
    }

    public StageResult stageWorld(File worldDir, File repoDir, StageProgressListener listener) throws IOException {
        int changedRegions = 0;
        int changedChunks = 0;
        int changedAuxiliaryEntries = 0;
        long bytesWritten = 0;

        String worldKey = worldDir.getName();
        Set<DirtyChunkIndex.ChunkKey> dirtyChunks = DirtyChunkIndex.snapshotWorld(worldKey);
        List<StageEntry> entries = collectRegionEntries(worldDir, repoDir, dirtyChunks);
        int totalEntries = entries.size();
        for (int index = 0; index < totalEntries; index++) {
            StageEntry entry = entries.get(index);
            ChunkExploder.ExplosionResult result = ChunkExploder.explodeRegionFile(entry.regionFile, entry.stagingRegionDir, entry.dirtyLocalChunks);
            if (result.hasChanges()) {
                changedRegions++;
                changedChunks += result.getChunkCount();
                bytesWritten += result.getBytesWritten();
            }

            if (listener != null) {
                int percent = totalEntries == 0 ? 80 : Math.max(1, (index + 1) * 80 / totalEntries);
                listener.onProgress(new StageProgress(percent, changedRegions, changedChunks, changedAuxiliaryEntries, bytesWritten));
            }
        }

        AuxiliaryStageResult auxiliaryResult = stageAuxiliaryWorldData(worldDir.toPath(), repoDir.toPath().resolve("staging"));
        changedAuxiliaryEntries += auxiliaryResult.changedEntries;
        bytesWritten += auxiliaryResult.bytesWritten;

        if (listener != null) {
            listener.onProgress(new StageProgress(90, changedRegions, changedChunks, changedAuxiliaryEntries, bytesWritten));
        }

        return new StageResult(changedRegions, changedChunks, changedAuxiliaryEntries, bytesWritten);
    }

    private AuxiliaryStageResult stageAuxiliaryWorldData(Path worldPath, Path stagingRoot) throws IOException {
        int changedEntries = 0;
        long bytesWritten = 0;

        try (java.util.stream.Stream<Path> stream = Files.walk(worldPath)) {
            List<Path> paths = new ArrayList<>();
            stream.forEach(paths::add);
            for (Path source : paths) {
                Path relative = worldPath.relativize(source);
                if (relative.toString().isEmpty()) {
                    continue;
                }

                String normalized = relative.toString().replace('\\', '/');
                if (isExcludedAuxiliaryPath(normalized)) {
                    continue;
                }

                Path target = stagingRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    continue;
                }

                if (copyIfChanged(source, target)) {
                    changedEntries++;
                    bytesWritten += Files.size(source);
                }
            }
        }

        return new AuxiliaryStageResult(changedEntries, bytesWritten);
    }

    private boolean isExcludedAuxiliaryPath(String normalizedRelativePath) {
        if (normalizedRelativePath.equals("session.lock")) {
            return true;
        }

        if (normalizedRelativePath.equals("region") || normalizedRelativePath.startsWith("region/")) {
            return true;
        }

        if (normalizedRelativePath.equals("DIM-1/region") || normalizedRelativePath.startsWith("DIM-1/region/")) {
            return true;
        }

        if (normalizedRelativePath.equals("DIM1/region") || normalizedRelativePath.startsWith("DIM1/region/")) {
            return true;
        }

        return false;
    }

    private boolean copyIfChanged(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());

        if (Files.exists(target) && Files.isRegularFile(target)) {
            long sourceSize = Files.size(source);
            long targetSize = Files.size(target);
            long sourceMtime = Files.getLastModifiedTime(source).toMillis();
            long targetMtime = Files.getLastModifiedTime(target).toMillis();
            if (sourceSize == targetSize && sourceMtime == targetMtime) {
                return false;
            }
        }

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return true;
    }

    private static final class AuxiliaryStageResult {
        private final int changedEntries;
        private final long bytesWritten;

        private AuxiliaryStageResult(int changedEntries, long bytesWritten) {
            this.changedEntries = changedEntries;
            this.bytesWritten = bytesWritten;
        }
    }

    private List<StageEntry> collectRegionEntries(File worldDir, File repoDir, Set<DirtyChunkIndex.ChunkKey> dirtyChunks) throws IOException {
        if (dirtyChunks == null || dirtyChunks.isEmpty()) {
            return Collections.emptyList();
        }

        Path worldPath = worldDir.toPath();
        Path stagingRoot = repoDir.toPath().resolve("staging");

        Map<String, StageEntry> byRegionPath = new HashMap<>();
        for (DirtyChunkIndex.ChunkKey dirtyChunk : dirtyChunks) {
            DimensionRegionMapping mapping = mapDimension(worldPath, stagingRoot, dirtyChunk.getDimensionId());
            if (mapping == null) {
                continue;
            }

            int regionX = Math.floorDiv(dirtyChunk.getChunkX(), 32);
            int regionZ = Math.floorDiv(dirtyChunk.getChunkZ(), 32);
            int localX = Math.floorMod(dirtyChunk.getChunkX(), 32);
            int localZ = Math.floorMod(dirtyChunk.getChunkZ(), 32);

            Path regionFile = mapping.sourceRegionDir.resolve("r." + regionX + "." + regionZ + ".mca");
            if (!Files.exists(regionFile) || !Files.isRegularFile(regionFile)) {
                continue;
            }

            String key = regionFile.toAbsolutePath().normalize().toString();
            StageEntry entry = byRegionPath.get(key);
            if (entry == null) {
                Files.createDirectories(mapping.stagingRegionDir);
                entry = new StageEntry(regionFile, mapping.stagingRegionDir);
                byRegionPath.put(key, entry);
            }
            entry.dirtyLocalChunks.add(new LocalChunkKey(localX, localZ));
        }

        return new ArrayList<>(byRegionPath.values());
    }

    private DimensionRegionMapping mapDimension(Path worldPath, Path stagingRoot, int dimensionId) {
        if (dimensionId == 0) {
            return new DimensionRegionMapping(
                worldPath.resolve("region"),
                stagingRoot.resolve("overworld").resolve("region")
            );
        }
        if (dimensionId == -1) {
            return new DimensionRegionMapping(
                worldPath.resolve("DIM-1").resolve("region"),
                stagingRoot.resolve("DIM-1").resolve("region")
            );
        }
        if (dimensionId == 1) {
            return new DimensionRegionMapping(
                worldPath.resolve("DIM1").resolve("region"),
                stagingRoot.resolve("DIM1").resolve("region")
            );
        }
        return null;
    }

    private static final class DimensionRegionMapping {
        private final Path sourceRegionDir;
        private final Path stagingRegionDir;

        private DimensionRegionMapping(Path sourceRegionDir, Path stagingRegionDir) {
            this.sourceRegionDir = sourceRegionDir;
            this.stagingRegionDir = stagingRegionDir;
        }
    }

    private static final class StageEntry {
        private final Path regionFile;
        private final Path stagingRegionDir;
        private final Set<LocalChunkKey> dirtyLocalChunks = new HashSet<>();

        private StageEntry(Path regionFile, Path stagingRegionDir) {
            this.regionFile = regionFile;
            this.stagingRegionDir = stagingRegionDir;
        }
    }

    public static final class LocalChunkKey {
        private final int localX;
        private final int localZ;

        public LocalChunkKey(int localX, int localZ) {
            this.localX = localX;
            this.localZ = localZ;
        }

        public int getLocalX() {
            return localX;
        }

        public int getLocalZ() {
            return localZ;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof LocalChunkKey)) {
                return false;
            }
            LocalChunkKey other = (LocalChunkKey) obj;
            return localX == other.localX && localZ == other.localZ;
        }

        @Override
        public int hashCode() {
            int result = localX;
            result = 31 * result + localZ;
            return result;
        }
    }
}
