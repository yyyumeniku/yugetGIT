package com.yugetGIT.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProgressParser {

    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(Receiving objects|Resolving deltas|Writing objects):\\s+(\\d+)%.*?(?:\\((.*?)\\))?");

    public static class ParseResult {
        public final String stage;
        public final int percentage;
        public final String details;

        public ParseResult(String stage, int percentage, String details) {
            this.stage = stage;
            this.percentage = percentage;
            this.details = details != null ? details : "";
        }
    }

    public static ParseResult parseLine(String line) {
        Matcher matcher = PROGRESS_PATTERN.matcher(line);
        if (matcher.find()) {
            String stage = matcher.group(1);
            int percentage = Integer.parseInt(matcher.group(2));
            String details = matcher.groupCount() >= 3 ? matcher.group(3) : "";
            return new ParseResult(stage, percentage, details);
        }
        return null;
    }
}