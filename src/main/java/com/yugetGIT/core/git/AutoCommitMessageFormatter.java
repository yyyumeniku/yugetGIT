package com.yugetGIT.core.git;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AutoCommitMessageFormatter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_NOTE_LENGTH = 72;

    private AutoCommitMessageFormatter() {
    }

    public static String format(String worldName, String trigger, int changedChunks, int playerCount, String note) {
        String safeWorld = worldName == null || worldName.trim().isEmpty() ? "unknown-world" : worldName.trim();
        String safeTrigger = trigger == null || trigger.trim().isEmpty() ? "WORLD_SAVE" : trigger.trim();
        int safeChunks = Math.max(changedChunks, 0);
        int safePlayers = Math.max(playerCount, 0);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String safeNote = sanitizeNote(note);

        String subject;
        if (safeTrigger.startsWith("MANUAL")) {
            subject = "Manual save (" + timestamp + ")";
        } else {
            subject = "Auto save (" + timestamp + ")";
        }

        StringBuilder details = new StringBuilder();
        details.append(safeTrigger)
            .append(" world=\"")
            .append(safeWorld)
            .append("\"")
            .append(" chunks=")
            .append(safeChunks)
            .append(" players=")
            .append(safePlayers)
            .append(" at=")
            .append(timestamp)
        ;

        if (safeNote != null) {
            details.append("\nnote=").append(safeNote);
        }

        return subject + "\n" + details;
    }

    private static String sanitizeNote(String note) {
        if (note == null) {
            return null;
        }

        String normalized = note.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.isEmpty()) {
            return null;
        }

        normalized = normalized.replace('"', '\'');
        if (normalized.length() > MAX_NOTE_LENGTH) {
            return normalized.substring(0, MAX_NOTE_LENGTH - 3) + "...";
        }

        return normalized;
    }
}