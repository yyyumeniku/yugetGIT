package com.yugetGIT.util;

import java.util.concurrent.atomic.AtomicInteger;

public final class SaveEventGuard {

    private static final AtomicInteger SUPPRESS_DEPTH = new AtomicInteger(0);

    private SaveEventGuard() {
    }

    public static void enter() {
        SUPPRESS_DEPTH.incrementAndGet();
    }

    public static void exit() {
        int current;
        do {
            current = SUPPRESS_DEPTH.get();
            if (current <= 0) {
                return;
            }
        } while (!SUPPRESS_DEPTH.compareAndSet(current, current - 1));
    }

    public static boolean isSuppressed() {
        return SUPPRESS_DEPTH.get() > 0;
    }
}
