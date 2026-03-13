package com.yugetGIT.core.git;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import com.yugetGIT.util.BackgroundExecutor;
import com.yugetGIT.core.mca.WorldSnapshotStager;
import java.util.function.Consumer;

public class CommitBuilder {

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
            feedback.accept("[yugetGIT] Save skipped: another backup is already running.");
            completion.run();
            return;
        }

        BackgroundExecutor.execute(() -> {
            try {
                WorldSnapshotStager stager = new WorldSnapshotStager();
                feedback.accept("[yugetGIT] Scanning region changes...");
                WorldSnapshotStager.StageResult stageResult = stager.stageWorld(worldDir, repoDir, update -> {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Staging", update.getPercent(), update.getChangedChunks(), update.getBytesWritten()));
                    }
                });

                if (!stageResult.hasChanges()) {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Idle", 100, 0, 0));
                    }
                    feedback.accept("[yugetGIT] No changed chunks found.");
                    return;
                }

                feedback.accept("[yugetGIT] Staged " + stageResult.getChangedChunks() + " chunks from " + stageResult.getChangedRegions() + " regions.");
                if (progress != null) {
                    progress.accept(new ProgressSnapshot("Indexing", 90, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                }

                GitExecutor.GitResult addResult = GitExecutor.execute(repoDir, 180, "add", "-A", "staging", "meta", ".gitattributes");
                if (!addResult.isSuccess()) {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Failed", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    feedback.accept("[yugetGIT] Git add failed: " + addResult.stderr);
                    return;
                }

                feedback.accept("[yugetGIT] Committing snapshot...");
                GitExecutor.GitResult commitResult = GitExecutor.execute(repoDir, 120, "commit", "-m", message);
                
                if (commitResult.isSuccess()) {
                    feedback.accept("[yugetGIT] Backup committed: " + message);
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Committed", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    GitExecutor.execute(repoDir, 120, "gc", "--auto");
                    GitExecutor.execute(repoDir, 120, "repack", "-a", "-d");
                } else if (commitResult.stdout.contains("nothing to commit") || commitResult.stderr.contains("nothing to commit")) {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Idle", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    feedback.accept("[yugetGIT] No new changes since last backup.");
                } else {
                    if (progress != null) {
                        progress.accept(new ProgressSnapshot("Failed", 100, stageResult.getChangedChunks(), stageResult.getBytesWritten()));
                    }
                    feedback.accept("[yugetGIT] Commit failed: " + commitResult.stderr);
                }

            } catch (Exception e) {
                feedback.accept("[yugetGIT] Commit error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                COMMIT_IN_PROGRESS.set(false);
                completion.run();
            }
        });
    }
}
