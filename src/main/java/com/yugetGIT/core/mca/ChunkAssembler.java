package com.yugetGIT.core.mca;

import net.querz.mca.Chunk;
import net.querz.mca.LoadFlags;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChunkAssembler {

    public static void assembleRegionFile(Path stagingRegionDir, Path mcaOutPath) throws IOException {
        if (!Files.exists(stagingRegionDir) || !Files.isDirectory(stagingRegionDir)) {
            return;
        }

        File mcaFile = mcaOutPath.toFile();
        if (!mcaFile.getParentFile().exists()) {
            mcaFile.getParentFile().mkdirs();
        }
        
        String dirName = stagingRegionDir.toFile().getName();
        int regionX = 0, regionZ = 0;
        if (dirName.startsWith("r.")) {
            String[] rParts = dirName.split("\\.");
            if (rParts.length >= 3) {
                try {
                    regionX = Integer.parseInt(rParts[1]);
                    regionZ = Integer.parseInt(rParts[2]);
                } catch (NumberFormatException ignored) {}
            }
        }
        
        MCAFile loadedMca;
        if (Files.exists(mcaOutPath)) {
            loadedMca = MCAUtil.read(mcaFile, LoadFlags.RAW);
            if (loadedMca == null) {
                loadedMca = new MCAFile(regionX, regionZ);
            }
        } else {
            loadedMca = new MCAFile(regionX, regionZ);
        }
        final MCAFile mca = loadedMca;

        try (java.util.stream.Stream<Path> stream = Files.list(stagingRegionDir)) {
            stream.forEach(chunkFile -> {
                String name = chunkFile.toFile().getName();
                if (name.startsWith("c.") && name.endsWith(".nbt")) {
                    String[] parts = name.split("\\.");
                    if (parts.length >= 3) {
                        try {
                            int x = Integer.parseInt(parts[1]);
                            int z = Integer.parseInt(parts[2]);

                            // ChunkExploder writes compressed NBT, so restore must read compressed too.
                            CompoundTag chunkData = (CompoundTag) NBTUtil.read(chunkFile.toFile(), true).getTag();

                            Chunk chunk = Chunk.newChunk();

                            // Bypass ClassCastException parsing by enforcing raw
                            Field dataField = Chunk.class.getDeclaredField("data");
                            dataField.setAccessible(true);
                            dataField.set(chunk, chunkData);

                            Field rawField = Chunk.class.getDeclaredField("raw");
                            rawField.setAccessible(true);
                            rawField.setBoolean(chunk, true);

                            mca.setChunk(x, z, chunk);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        }

        MCAUtil.write(mca, mcaFile);
    }
}