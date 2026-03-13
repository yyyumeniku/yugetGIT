package com.yugetGIT.core.mca;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ChunkTimestamp {

    private static final Map<String, Integer> TIMESTAMPS = new HashMap<>();
    private static File saveFile;

    public static void init(File repoDir) {
        TIMESTAMPS.clear();
        saveFile = new File(repoDir, "meta/timestamps.properties");
        if (saveFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(saveFile)) {
                props.load(in);
                for (String key : props.stringPropertyNames()) {
                    TIMESTAMPS.put(key, Integer.parseInt(props.getProperty(key)));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void clearAll() {
        TIMESTAMPS.clear();
        if (saveFile != null && saveFile.exists()) {
            saveFile.delete();
        }
    }

    public static int get(String regionName, int chunkX, int chunkZ) {
        String key = regionName + "." + chunkX + "." + chunkZ;
        return TIMESTAMPS.getOrDefault(key, 0);
    }

    public static void update(String regionName, int chunkX, int chunkZ, int timestamp) {
        String key = regionName + "." + chunkX + "." + chunkZ;
        TIMESTAMPS.put(key, timestamp);
    }

    public static void save() {
        if (saveFile == null) return;
        saveFile.getParentFile().mkdirs();
        Properties props = new Properties();
        for (Map.Entry<String, Integer> entry : TIMESTAMPS.entrySet()) {
            props.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }
        try (FileOutputStream out = new FileOutputStream(saveFile)) {
            props.store(out, "yugetGIT Chunk Timestamps");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}