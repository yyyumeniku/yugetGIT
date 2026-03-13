package com.yugetGIT.core.git;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
    private final AutoMessageContext autoMessageContext;

    public static final class AutoMessageContext {
        private final String worldName;
        private final String trigger;
        private final int playerCount;
        private final String note;

        public AutoMessageContext(String worldName, String trigger, int playerCount) {
            this(worldName, trigger, playerCount, null);
        }

        public AutoMessageContext(String worldName, String trigger, int playerCount, String note) {
            this.worldName = worldName;
            this.trigger = trigger;
            this.playerCount = playerCount;
            this.note = note;
        }

        public String getWorldName() {
            return worldName;
        }

        public String getTrigger() {
            return trigger;
        }

        public int getPlayerCount() {
            return playerCount;
        }

        public String getNote() {
            return note;
        }
    }

    public CommitBuilder(File repoDir, File worldDir, String message) {
        this(repoDir, worldDir, message, null);
    }

    public CommitBuilder(File repoDir, File worldDir, String message, AutoMessageContext autoMessageContext) {
        this.repoDir = repoDir;
        this.worldDir = worldDir;
        this.message = message;
        this.autoMessageContext = autoMessageContext;
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
                String commitMessage = buildCommitMessage(stageResult);
                String subject = commitMessage;
                String body = null;
                int newline = commitMessage.indexOf('\n');
                if (newline >= 0) {
                    subject = commitMessage.substring(0, newline).trim();
                    body = commitMessage.substring(newline + 1).trim();
                }

                List<String> commitArgs = new ArrayList<>();
                commitArgs.add("commit");
                commitArgs.add("-m");
                commitArgs.add(subject);
                if (body != null && !body.isEmpty()) {
                    commitArgs.add("-m");
                    commitArgs.add(body);
                }

                GitExecutor.GitResult commitResult = GitExecutor.execute(repoDir, 120, commitArgs.toArray(new String[0]));
                
                if (commitResult.isSuccess()) {
                    double backupSizeMb = stageResult.getBytesWritten() / (1024.0 * 1024.0);
                    feedback.accept(String.format("Backup committed (%.2f MB)", backupSizeMb));
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

    private String buildCommitMessage(WorldSnapshotStager.StageResult stageResult) {
        if (autoMessageContext == null) {
            return message;
        }

        return AutoCommitMessageFormatter.format(
            autoMessageContext.getWorldName(),
            autoMessageContext.getTrigger(),
            stageResult.getChangedChunks(),
            autoMessageContext.getPlayerCount(),
            autoMessageContext.getNote()
        );
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
