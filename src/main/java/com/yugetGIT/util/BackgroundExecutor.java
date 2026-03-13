package com.yugetGIT.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackgroundExecutor {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void execute(Runnable task) {
        executor.submit(task);
    }
    
    public static void shutdown() {
        executor.shutdown();
    }
}