package com.yugetGIT.util;

public final class SaveProgressTracker {

    public static final class Snapshot {
        private final boolean active;
        private final String stage;
        private final int percent;
        private final int changedChunks;
        private final long bytesWritten;
        private final String detail;

        public Snapshot(boolean active, String stage, int percent, int changedChunks, long bytesWritten, String detail) {
            this.active = active;
            this.stage = stage;
            this.percent = percent;
            this.changedChunks = changedChunks;
            this.bytesWritten = bytesWritten;
            this.detail = detail;
        }

        public boolean isActive() {
            return active;
        }

        public String getStage() {
            return stage;
        }

        public int getPercent() {
            return percent;
        }

        public int getChangedChunks() {
            return changedChunks;
        }

        public long getBytesWritten() {
            return bytesWritten;
        }

        public String getDetail() {
            return detail;
        }
    }

    private static volatile boolean active = false;
    private static volatile String stage = "Idle";
    private static volatile int percent = 0;
    private static volatile int changedChunks = 0;
    private static volatile long bytesWritten = 0L;
    private static volatile String detail = "";

    private SaveProgressTracker() {
    }

    public static synchronized void start(String initialStage, int initialPercent, String initialDetail) {
        active = true;
        stage = initialStage;
        percent = clampPercent(initialPercent);
        changedChunks = 0;
        bytesWritten = 0L;
        detail = initialDetail == null ? "" : initialDetail;
    }

    public static synchronized void update(String newStage, int newPercent, int newChangedChunks, long newBytesWritten) {
        active = true;
        stage = newStage == null ? stage : newStage;
        percent = clampPercent(newPercent);
        changedChunks = Math.max(0, newChangedChunks);
        bytesWritten = Math.max(0L, newBytesWritten);
        detail = "";
    }

    public static synchronized void finish(boolean success, String message) {
        active = false;
        stage = success ? "Completed" : "Failed";
        percent = 100;
        detail = message == null ? "" : message;
    }

    public static Snapshot snapshot() {
        return new Snapshot(active, stage, percent, changedChunks, bytesWritten, detail);
    }

    private static int clampPercent(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 100) {
            return 100;
        }
        return value;
    }
}
