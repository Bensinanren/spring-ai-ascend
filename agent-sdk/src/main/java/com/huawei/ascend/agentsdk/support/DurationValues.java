package com.huawei.ascend.agentsdk.support;

import java.time.Duration;
import java.util.Locale;

public final class DurationValues {

    private DurationValues() {
    }

    public static Duration duration(Object value, Duration fallback, String label) {
        if (value == null) {
            return fallback;
        }
        Duration duration = parse(value, label);
        if (duration.isZero() || duration.isNegative()) {
            throw new ValidationException("Field '" + label + "' must be positive, got: " + value);
        }
        return duration;
    }

    private static Duration parse(Object value, String label) {
        if (value instanceof Number number) {
            return Duration.ofSeconds(number.longValue());
        }
        String text = String.valueOf(value).trim();
        try {
            if (text.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2).trim()));
            }
            if (text.endsWith("s") && !text.toUpperCase(Locale.ROOT).startsWith("PT")) {
                return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1).trim()));
            }
            if (text.endsWith("m") && !text.toUpperCase(Locale.ROOT).startsWith("PT")) {
                return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1).trim()));
            }
            if (text.chars().allMatch(Character::isDigit)) {
                return Duration.ofSeconds(Long.parseLong(text));
            }
            return Duration.parse(text);
        } catch (RuntimeException error) {
            throw new ValidationException(
                    "Field '" + label + "' must be a number of seconds, '30s'/'500ms'/'2m', or ISO-8601, got: "
                            + value,
                    error);
        }
    }
}
