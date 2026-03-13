package com.yugetGIT.core.git;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import com.yugetGIT.util.BackgroundExecutor;
import com.yugetGIT.util.SaveProgressTracker;
import com.yugetGIT.core.mca.WorldSnapshotStager;
import java.util.function.Consumer;

public class CommitBuilder {

    private static final int MAINTENANCE_INTERVAL_COMMITS = 10;

    public static final class ProgressSnapshot {
        private final String stage;
        private final int percent;
        private final int changedChunks;
        private final long bytesWritten;

        public ProgressSnapshot(String stage, int percent, int changedChunks, long bytesWritten) {
            this.stage = stage;
            this.percent = percent;
            this.changedChunks = changedChunks;
            this.bytesWritten = bytesWritten;
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
    }

    private static final AtomicBoolean COMMIT_IN_PROGRESS = new AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicInteger SUCCESSFUL_COMMITS = new java.util.concurrent.atomic.AtomicInteger(0);

    private final File repoDir;
    private final File worldDir;
    private final String message;

    public CommitBuilder(File repoDir, File worldDir, String message) {
        this.repoDir = repoDir;
        this.worldDir = worldDir;
        this.message = message;
    }

    public void commitAsync() {
        commitAsync(System.out::println, null, () -> { });
    }

    public void commitAsync(Consumer<String> feedback) {
        commitAsync(feedback, null, () -> { });
    }

    public void commitAsync(Consumer<String> feedback, Runnable completion) {
        commitAsync(feedback, null, completion);
    }

    public void commitAsync(Consumer<String> feedback, Consumer<ProgressSnapshot> progress, Runnable completion) {
        if (!COMMIT_IN_PROGRESS.compareAndSet(false, true)) {
            completion.run();
            return;
        }

        BackgroundExecutor.execute(() -> {
            try {
                WorldSnapshotStager stager = new WorldSnapshotStager();
                feedback.accept("Scanning region changes...");
                SaveProgressTracker.start("Staging", 10, "Scanning region changes");
                WorldSnapshotStager.StageResult stageResult = stager.stageWorld(worldDir, repoDir, update -> {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Staging", update.getPercent(), update.getChangedChunks(), update.getBytesWritten()));
                    }
                    SaveProgressTracker.update("Staging", update.getPercent(), update.getChangedChunks(), update.getBytesWritten());
                });

                if (!stageResult.hasChanges()) {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Idle", 100, 0, 0));
                    }
                    feedback.accept("No changed chunks found.");
                    SaveProgressTracker.finish(true, "No changed chunks found.");
                    return;
                }

                feedback.accept("Staged " + stageResult.getChangedChunks() + " chunks from " + stageResult.getChangedRegions() + " regions.");
                if (progress != null) {
                    progress.accept(new ProgressSnapshot("Indexing", 90, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                }
                SaveProgressTracker.update("Indexing", 90, stageResult.getChangedChunks(), stageResult.getBytesWritten());

                GitExecutor.GitResult addResult = GitExecutor.execute(repoDir, 180, "add", "-A", "staging", "meta", ".gitattributes");
                if (!addResult.isSuccess()) {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Failed", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    feedback.accept("Git add failed: " + addResult.stderr);
                    SaveProgressTracker.finish(false, "Git add failed.");
                    return;
                }

                GitExecutor.GitResult stagedStatusResult = GitExecutor.execute(repoDir, 30, "status", "--porcelain", "--", "staging", "meta", ".gitattributes");
                if (!stagedStatusResult.isSuccess()) {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Failed", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    feedback.accept("Unable to inspect staged changes: " + stagedStatusResult.stderr);
                    SaveProgressTracker.finish(false, "Staged change inspection failed.");
                    return;
                }

                if (stagedStatusResult.stdout == null || stagedStatusResult.stdout.trim().isEmpty()) {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Idle", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    feedback.accept("No meaningful file changes to commit.");
                    SaveProgressTracker.finish(true, "No new changes.");
                    return;
                }

                feedback.accept("Committing snapshot...");
                GitExecutor.GitResult commitResult = GitExecutor.execute(repoDir, 120, "commit", "-m", message);
                
                if (commitResult.isSuccess()) {
                    double backupSizeMb = stageResult.getBytesWritten() / (1024.0 * 1024.0);
                    feedback.accept(String.format("Backup committed: %s (%.2f MB)", message, backupSizeMb));
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Committed", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    SaveProgressTracker.finish(true, "Backup committed.");
                    runPeriodicMaintenanceIfNeeded();
                } else if (commitResult.stdout.contains("nothing to commit") || commitResult.stderr.contains("nothing to commit")) {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Idle", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    feedback.accept("No new changes since last backup.");
                    SaveProgressTracker.finish(true, "No new changes.");
                } else {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Failed", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    feedback.accept("Commit failed: " + commitResult.stderr);
                    SaveProgressTracker.finish(false, "Commit failed.");
                }

            } catch (Exception e) {
                feedback.accept("Commit error: " + e.getMessage());
                SaveProgressTracker.finish(false, "Commit error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                COMMIT_IN_PROGRESS.set(false);
                completion.run();
            }
        });
    }

    private void runPeriodicMaintenanceIfNeeded() {
        int commits = SUCCESSFUL_COMMITS.incrementAndGet();
        if (commits % MAINTENANCE_INTERVAL_COMMITS != 0) {
            return;
        }

        try {
            GitExecutor.execute(repoDir, 120, "gc", "--auto");
            GitExecutor.execute(repoDir, 120, "repack", "-a", "-d");
            GitLfsManager.pruneLocalCacheIfAvailable(repoDir);
        } catch (Exception ignored) {
        }
    }
}
