package com.huawei.ascend.agentsdk.adapter.deepagent;

import com.huawei.ascend.agentsdk.support.OptionValues;
import java.util.Map;

public record OpenJiuwenDeepAgentOptions(
        int maxIterations,
        String skillMode,
        String workspacePath,
        String language,
        boolean enableTaskLoop,
        boolean enableTaskPlanning,
        Double completionTimeout) {

    public static OpenJiuwenDeepAgentOptions from(Map<String, Object> options) {
        int maxIterations = OptionValues.intOption(options, "maxIterations", 15);
        return new OpenJiuwenDeepAgentOptions(
                maxIterations,
                stringOption(options, "skillMode", "all"),
                stringOption(options, "workspacePath", "./"),
                stringOption(options, "language", "cn"),
                booleanOption(options, "enableTaskLoop", false),
                booleanOption(options, "enableTaskPlanning", false),
                doubleOption(options, "completionTimeout"));
    }

    private static String stringOption(Map<String, Object> options, String key, String fallback) {
        Object value = options.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new com.huawei.ascend.agentsdk.support.ValidationException(
                "Option '" + key + "' must be true or false, got: " + value);
    }

    private static Double doubleOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            throw new com.huawei.ascend.agentsdk.support.ValidationException(
                    "Option '" + key + "' must be a number, got: " + value,
                    error);
        }
    }
}
