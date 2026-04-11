package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LineSeparatorDetector {

    /**
     * Enum representing different types of line separators.
     */
    public enum LineSeparator {
        UNIX("\n"),
        WINDOWS("\r\n"),
        OLD_MAC("\r"),
        MIXED("Mixed"),
        NONE("None");

        private final String separator;

        LineSeparator(String separator) {
            this.separator = separator;
        }

        public String getSeparator() {
            return separator;
        }
    }

    /**
     * Determines the dominant line separator used in the given text.
     *
     * @param text The input text to analyze.
     * @return The dominant LineSeparator.
     */
    public static LineSeparator detectDominantLineSeparator(String text) {
        if (text == null || text.isEmpty()) {
            return LineSeparator.NONE;
        }

        int unixCount = 0;
        int windowsCount = 0;
        int oldMacCount = 0;

        Pattern lineSeparatorPattern = Pattern.compile("\\r\\n|\\n|\\r");
        Matcher matcher = lineSeparatorPattern.matcher(text);

        while (matcher.find()) {
            String sep = matcher.group();
            switch (sep) {
                case "\r\n":
                    windowsCount++;
                    break;
                case "\n":
                    unixCount++;
                    break;
                case "\r":
                    oldMacCount++;
                    break;
                default:
                    // Should not reach here
                    break;
            }
        }

        // Determine dominant separator
        if (windowsCount > 0 || unixCount > 0 || oldMacCount > 0) {
            int max = Math.max(windowsCount, Math.max(unixCount, oldMacCount));
            int countTypes = 0;
            if (windowsCount > 0)
                countTypes++;
            if (unixCount > 0)
                countTypes++;
            if (oldMacCount > 0)
                countTypes++;

            if (countTypes > 1) {
                return LineSeparator.MIXED;
            }

            if (max == windowsCount) {
                return LineSeparator.WINDOWS;
            } else if (max == unixCount) {
                return LineSeparator.UNIX;
            } else {
                return LineSeparator.OLD_MAC;
            }
        }

        return LineSeparator.NONE;
    }

    /**
     * Checks if the text contains mixed line separators.
     *
     * @param text The input text to analyze.
     * @return True if mixed line separators are found, false otherwise.
     */
    public static boolean hasMixedLineSeparators(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        boolean hasUnix = text.contains("\n") && !text.contains("\r\n");
        boolean hasWindows = text.contains("\r\n");
        boolean hasOldMac = text.contains("\r") && !text.contains("\r\n");

        int types = 0;
        if (hasUnix)
            types++;
        if (hasWindows)
            types++;
        if (hasOldMac)
            types++;

        return types > 1;
    }

    /**
     * Retrieves all unique line separators used in the text.
     *
     * @param text The input text to analyze.
     * @return A set of unique line separators found in the text.
     */
    public static java.util.Set<String> getUniqueLineSeparators(String text) {
        java.util.Set<String> separators = new java.util.HashSet<>();
        if (text == null || text.isEmpty()) {
            return separators;
        }

        Pattern lineSeparatorPattern = Pattern.compile("\\r\\n|\\n|\\r");
        Matcher matcher = lineSeparatorPattern.matcher(text);

        while (matcher.find()) {
            separators.add(matcher.group());
        }

        return separators;
    }
}