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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    public static ExplosionResult explodeRegionFile(Path mcaPath, Path outputDir, Set<WorldSnapshotStager.LocalChunkKey> dirtyLocalChunks) throws IOException {
        if (!Files.exists(mcaPath) || dirtyLocalChunks == null || dirtyLocalChunks.isEmpty()) {
            return new ExplosionResult(false, 0, 0);
        }

        File mcaFile = mcaPath.toFile();
        String regionName = mcaFile.getName().replace(".mca", "");

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
        for (WorldSnapshotStager.LocalChunkKey dirtyChunk : dirtyLocalChunks) {
            int x = dirtyChunk.getLocalX();
            int z = dirtyChunk.getLocalZ();
            Chunk chunk = mca.getChunk(x, z);
            if (chunk == null) {
                continue;
            }

            try {
                Path chunkOutPath = regionOutDir.resolve("c." + x + "." + z + ".nbt");
                boolean baselineMissing = !Files.exists(chunkOutPath);

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
        Map<Byte, CompoundTag> leftByY = mapSectionsByY(leftList);
        Map<Byte, CompoundTag> rightByY = mapSectionsByY(rightList);
        if (leftByY.size() != rightByY.size()) {
            return false;
        }

        for (Map.Entry<Byte, CompoundTag> leftEntry : leftByY.entrySet()) {
            CompoundTag leftSection = leftEntry.getValue();
            CompoundTag rightSection = rightByY.get(leftEntry.getKey());
            if (rightSection == null) {
                return false;
            }

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

    private static Map<Byte, CompoundTag> mapSectionsByY(ListTag<?> sectionList) {
        Map<Byte, CompoundTag> byY = new HashMap<>();
        for (Object sectionObj : sectionList) {
            if (!(sectionObj instanceof CompoundTag)) {
                continue;
            }
            CompoundTag section = (CompoundTag) sectionObj;
            Object yObj = section.get("Y");
            if (yObj instanceof Number) {
                byY.put(((Number) yObj).byteValue(), section);
            }
        }
        return byY;
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
