package com.yugetGIT.core.mca;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WorldSnapshotStager {

    public interface StageProgressListener {
        void onProgress(StageProgress update);
    }

    public static final class StageProgress {
        private final int percent;
        private final int changedRegions;
        private final int changedChunks;
        private final long bytesWritten;

        public StageProgress(int percent, int changedRegions, int changedChunks, long bytesWritten) {
            this.percent = percent;
            this.changedRegions = changedRegions;
            this.changedChunks = changedChunks;
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

        public long getBytesWritten() {
            return bytesWritten;
        }
    }

    public static final class StageResult {
        private final int changedRegions;
        private final int changedChunks;
        private final long bytesWritten;

        public StageResult(int changedRegions, int changedChunks, long bytesWritten) {
            this.changedRegions = changedRegions;
            this.changedChunks = changedChunks;
            this.bytesWritten = bytesWritten;
        }

        public int getChangedRegions() {
            return changedRegions;
        }

        public int getChangedChunks() {
            return changedChunks;
        }

        public long getBytesWritten() {
            return bytesWritten;
        }

        public boolean hasChanges() {
            return changedChunks > 0;
        }
    }

    public StageResult stageWorld(File worldDir, File repoDir) throws IOException {
        return stageWorld(worldDir, repoDir, null);
    }

    public StageResult stageWorld(File worldDir, File repoDir, StageProgressListener listener) throws IOException {
        ChunkTimestamp.init(repoDir);

        int changedRegions = 0;
        int changedChunks = 0;
        long bytesWritten = 0;

        List<StageEntry> entries = collectRegionEntries(worldDir, repoDir);
        int totalEntries = entries.size();
        for (int index = 0; index < totalEntries; index++) {
            StageEntry entry = entries.get(index);
            ChunkExploder.ExplosionResult result = ChunkExploder.explodeRegionFile(entry.regionFile, entry.stagingRegionDir);
            if (result.hasChanges()) {
                changedRegions++;
                changedChunks += result.getChunkCount();
                bytesWritten += result.getBytesWritten();
            }

            if (listener != null) {
                int percent = totalEntries == 0 ? 80 : Math.max(1, (index + 1) * 80 / totalEntries);
                listener.onProgress(new StageProgress(percent, changedRegions, changedChunks, bytesWritten));
            }
        }

        ChunkTimestamp.save();
        return new StageResult(changedRegions, changedChunks, bytesWritten);
    }

    private List<StageEntry> collectRegionEntries(File worldDir, File repoDir) throws IOException {
        List<StageEntry> entries = new ArrayList<>();
        for (DimensionRegionMapping mapping : buildMappings(worldDir, repoDir)) {
            if (!Files.exists(mapping.sourceRegionDir) || !Files.isDirectory(mapping.sourceRegionDir)) {
                continue;
            }

            Files.createDirectories(mapping.stagingRegionDir);
            for (Path regionFile : listRegionFiles(mapping.sourceRegionDir)) {
                entries.add(new StageEntry(regionFile, mapping.stagingRegionDir));
            }
        }
        return entries;
    }

    private List<Path> listRegionFiles(Path regionDir) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(regionDir)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("r."))
                .filter(path -> path.getFileName().toString().endsWith(".mca"))
                .forEach(paths::add);
        }
        return paths;
    }

    private List<DimensionRegionMapping> buildMappings(File worldDir, File repoDir) {
        Path worldPath = worldDir.toPath();
        Path stagingRoot = repoDir.toPath().resolve("staging");

        List<DimensionRegionMapping> mappings = new ArrayList<>();
        mappings.add(new DimensionRegionMapping(
            worldPath.resolve("region"),
            stagingRoot.resolve("overworld").resolve("region")
        ));
        mappings.add(new DimensionRegionMapping(
            worldPath.resolve("DIM-1").resolve("region"),
            stagingRoot.resolve("DIM-1").resolve("region")
        ));
        mappings.add(new DimensionRegionMapping(
            worldPath.resolve("DIM1").resolve("region"),
            stagingRoot.resolve("DIM1").resolve("region")
        ));

        return mappings;
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

        private StageEntry(Path regionFile, Path stagingRegionDir) {
            this.regionFile = regionFile;
            this.stagingRegionDir = stagingRegionDir;
        }
    }
}
