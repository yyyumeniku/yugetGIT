package com.yugetGIT.core.mca;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

        AuxiliaryStageResult auxiliaryResult = stageAuxiliaryWorldData(worldDir.toPath(), repoDir.toPath().resolve("staging"));
        changedChunks += auxiliaryResult.changedEntries;
        bytesWritten += auxiliaryResult.bytesWritten;

        if (listener != null) {
            listener.onProgress(new StageProgress(90, changedRegions, changedChunks, bytesWritten));
        }

        ChunkTimestamp.save();
        return new StageResult(changedRegions, changedChunks, bytesWritten);
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
