package com.yugetGIT.core.mca;

import net.querz.mca.Chunk;
import net.querz.mca.LoadFlags;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.CompoundTag;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class ChunkExploder {

    private static final Set<String> COMPARABLE_LEVEL_KEYS = new HashSet<>();
    private static final Set<String> COMPARABLE_SECTION_KEYS = new HashSet<>();

    static {
        COMPARABLE_LEVEL_KEYS.add("xPos");
        COMPARABLE_LEVEL_KEYS.add("zPos");
        COMPARABLE_LEVEL_KEYS.add("Biomes");
        COMPARABLE_LEVEL_KEYS.add("Sections");

        COMPARABLE_SECTION_KEYS.add("Y");
        COMPARABLE_SECTION_KEYS.add("Blocks");
        COMPARABLE_SECTION_KEYS.add("Data");
        COMPARABLE_SECTION_KEYS.add("Add");
    }

    public static final class ExplosionResult {
        private final boolean changed;
        private final int chunkCount;
        private final long bytesWritten;

        public ExplosionResult(boolean changed, int chunkCount, long bytesWritten) {
            this.changed = changed;
            this.chunkCount = chunkCount;
            this.bytesWritten = bytesWritten;
        }

        public boolean hasChanges() {
            return changed;
        }

        public int getChunkCount() {
            return chunkCount;
        }

        public long getBytesWritten() {
            return bytesWritten;
        }
    }

    public static ExplosionResult explodeRegionFile(Path mcaPath, Path outputDir) throws IOException {
        if (!Files.exists(mcaPath)) {
            return new ExplosionResult(false, 0, 0);
        }

        File mcaFile = mcaPath.toFile();
        String regionName = mcaFile.getName().replace(".mca", "");
        String dimensionName = outputDir.getParent() == null ? "overworld" : outputDir.getParent().getFileName().toString();
        String regionKeyPrefix = dimensionName + "/" + regionName;
        
        boolean hasChanges = false;
        int[] changedTimestamps = new int[1024];
        int[] currentRegionTimestamps = new int[1024];

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mcaFile, "r")) {
            if (raf.length() < 8192) {
                return new ExplosionResult(false, 0, 0);
            }
            raf.seek(4096);
            for (int i = 0; i < 1024; i++) {
                int lastUpdated = raf.readInt();
                int x = i % 32;
                int z = i / 32;
                currentRegionTimestamps[i] = lastUpdated;
                int currentTimestamp = ChunkTimestamp.get(regionKeyPrefix, x, z);
                
                if (lastUpdated > currentTimestamp) {
                    hasChanges = true;
                    changedTimestamps[i] = lastUpdated;
                } else {
                    changedTimestamps[i] = -1;
                }
            }
        }

        // If no chunks have progressed past our tracked timestamps, simply skip this region file entirely.
        if (!hasChanges) {
            return new ExplosionResult(false, 0, 0);
        }

        MCAFile mca = MCAUtil.read(mcaFile, LoadFlags.RAW);
        if (mca == null) {
            return new ExplosionResult(false, 0, 0);
        }

        Path regionOutDir = outputDir.resolve(regionName);
        if (!Files.exists(regionOutDir)) {
            Files.createDirectories(regionOutDir);
        }

        int writtenChunks = 0;
        long writtenBytes = 0;
        for (int i = 0; i < 1024; i++) {
            int x = i % 32;
            int z = i / 32;
            Chunk chunk = mca.getChunk(x, z);
            if (chunk == null) {
                continue;
            }

            try {
                Path chunkOutPath = regionOutDir.resolve("c." + x + "." + z + ".nbt");
                boolean isChangedChunk = changedTimestamps[i] != -1;
                boolean baselineMissing = !Files.exists(chunkOutPath);

                // If any chunk in this region changed, keep this region fully reconstructible at this commit.
                if (isChangedChunk || baselineMissing) {
                    CompoundTag chunkData = chunk.getHandle();
                    boolean shouldWrite = baselineMissing;
                    if (!shouldWrite) {
                        try {
                            Object existingTag = NBTUtil.read(chunkOutPath.toFile(), true).getTag();
                            if (existingTag instanceof CompoundTag) {
                                shouldWrite = !chunksEquivalent(chunkData, (CompoundTag) existingTag);
                            } else {
                                shouldWrite = true;
                            }
                        } catch (Exception ignored) {
                            shouldWrite = true;
                        }
                    }

                    if (shouldWrite) {
                        NBTUtil.write(chunkData, chunkOutPath.toFile(), true);
                        writtenBytes += Files.size(chunkOutPath);
                        writtenChunks++;
                    }
                }

                if (currentRegionTimestamps[i] > 0) {
                    ChunkTimestamp.update(regionKeyPrefix, x, z, currentRegionTimestamps[i]);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return new ExplosionResult(writtenChunks > 0, writtenChunks, writtenBytes);
    }

    private static boolean chunksEquivalent(CompoundTag current, CompoundTag existing) {
        Object currentLevelObj = current.get("Level");
        Object existingLevelObj = existing.get("Level");
        if (!(currentLevelObj instanceof CompoundTag) || !(existingLevelObj instanceof CompoundTag)) {
            return current.equals(existing);
        }

        CompoundTag currentLevel = (CompoundTag) currentLevelObj;
        CompoundTag existingLevel = (CompoundTag) existingLevelObj;

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(currentLevel.keySet());
        allKeys.addAll(existingLevel.keySet());

        for (String key : allKeys) {
            if (isVolatileLevelKey(key)) {
                continue;
            }
            if (!isComparableLevelKey(key)) {
                continue;
            }
            Object left = currentLevel.get(key);
            Object right = existingLevel.get(key);
            if (left == null && right == null) {
                continue;
            }
            if (left == null || right == null) {
                return false;
            }
            if ("Sections".equals(key)) {
                if (!sectionListsEquivalent(left, right)) {
                    return false;
                }
                continue;
            }
            if (!left.equals(right)) {
                return false;
            }
        }

        return true;
    }

    private static boolean sectionListsEquivalent(Object leftSections, Object rightSections) {
        if (!(leftSections instanceof ListTag) || !(rightSections instanceof ListTag)) {
            return leftSections.equals(rightSections);
        }

        ListTag<?> leftList = (ListTag<?>) leftSections;
        ListTag<?> rightList = (ListTag<?>) rightSections;
        if (leftList.size() != rightList.size()) {
            return false;
        }

        for (int i = 0; i < leftList.size(); i++) {
            Object leftSectionObj = leftList.get(i);
            Object rightSectionObj = rightList.get(i);
            if (!(leftSectionObj instanceof CompoundTag) || !(rightSectionObj instanceof CompoundTag)) {
                if (leftSectionObj == null && rightSectionObj == null) {
                    continue;
                }
                if (leftSectionObj == null || rightSectionObj == null || !leftSectionObj.equals(rightSectionObj)) {
                    return false;
                }
                continue;
            }

            CompoundTag leftSection = (CompoundTag) leftSectionObj;
            CompoundTag rightSection = (CompoundTag) rightSectionObj;
            for (String sectionKey : COMPARABLE_SECTION_KEYS) {

                Object sectionLeftValue = leftSection.get(sectionKey);
                Object sectionRightValue = rightSection.get(sectionKey);
                if (sectionLeftValue == null && sectionRightValue == null) {
                    continue;
                }
                if (sectionLeftValue == null || sectionRightValue == null) {
                    return false;
                }
                if (!sectionLeftValue.equals(sectionRightValue)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isComparableLevelKey(String key) {
        return COMPARABLE_LEVEL_KEYS.contains(key);
    }

    private static boolean isVolatileLevelKey(String key) {
        return "LastUpdate".equals(key)
            || "InhabitedTime".equals(key)
            || "Entities".equals(key)
            || "TileEntities".equals(key)
            || "TileTicks".equals(key)
            || "LiquidTicks".equals(key);
    }
}
