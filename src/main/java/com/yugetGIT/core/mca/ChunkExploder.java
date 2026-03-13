package com.yugetGIT.core.mca;

import net.querz.mca.Chunk;
import net.querz.mca.LoadFlags;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChunkExploder {

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
                    NBTUtil.write(chunkData, chunkOutPath.toFile(), true);
                    writtenBytes += Files.size(chunkOutPath);
                    writtenChunks++;
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
}
