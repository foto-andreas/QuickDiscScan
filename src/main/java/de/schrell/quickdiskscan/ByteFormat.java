package de.schrell.quickdiskscan;

import static de.schrell.quickdiskscan.I18n.decimal;
import static de.schrell.quickdiskscan.I18n.number;

final class ByteFormat {
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private ByteFormat() {}

    static String bytes(long bytes) {
        if (bytes < 1_000) {
            return number(bytes) + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1_000 && unit < UNITS.length - 1) {
            value /= 1_000;
            unit++;
        }
        int fractionDigits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
        return decimal(value, fractionDigits) + " " + UNITS[unit];
    }

    static String rate(long entries, long elapsedMillis) {
        if (elapsedMillis <= 0) {
            return "0/s";
        }
        return number(entries * 1_000L / elapsedMillis) + "/s";
    }

}
