package com.huawei.ascend.agentsdk.spec.tool;

import com.huawei.ascend.agentsdk.support.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class HttpToolResolver implements ToolResolver {
    @Override
    public boolean supports(String scheme) {
        return "http".equalsIgnoreCase(scheme);
    }

    @Override
    public ResolvedTool resolve(ToolSpec spec) {
        Map<String, Object> attributes = spec.ref().attributes();
        String url = ToolRefAttributes.required(attributes, "url");
        String method = ToolRefAttributes.string(attributes.getOrDefault("method", "POST")).toUpperCase(Locale.ROOT);
        HttpExecutionHandle handle = new HttpExecutionHandle(
                uri(url),
                method,
                headers(attributes.get("headers")),
                timeout(attributes.get("timeout")),
                booleanValue(attributes.get("followRedirects"), false, "followRedirects"),
                intValue(attributes.get("maxResponseBytes"), HttpExecutionHandle.DEFAULT_MAX_RESPONSE_BYTES,
                        "maxResponseBytes"),
                booleanValue(attributes.get("exposeErrorBody"), false, "exposeErrorBody"));
        return new WrappableTool(ToolRefAttributes.descriptor(spec), handle);
    }

    private static URI uri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new ValidationException("Invalid http tool url: " + url, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> headers(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }

    static Duration timeout(Object value) {
        if (value == null) {
            return Duration.ofSeconds(30);
        }
        Duration timeout = parseTimeout(value);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new ValidationException("Tool ref timeout must be positive, got: " + value);
        }
        return timeout;
    }

    private static boolean booleanValue(Object value, boolean fallback, String label) {
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
        throw new ValidationException("Tool ref " + label + " must be true or false, got: " + value);
    }

    private static int intValue(Object value, int fallback, String label) {
        if (value == null) {
            return fallback;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException error) {
                throw new ValidationException("Tool ref " + label + " must be an integer, got: " + value, error);
            }
        }
        if (parsed <= 0) {
            throw new ValidationException("Tool ref " + label + " must be positive, got: " + value);
        }
        return parsed;
    }

    /** Bare numbers mean SECONDS (the config-file convention); "30s"/"500ms"/"2m" and ISO-8601 also parse. */
    private static Duration parseTimeout(Object value) {
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
        } catch (RuntimeException e) {
            throw new ValidationException(
                    "Tool ref timeout must be a number of seconds, '30s'/'500ms'/'2m', or ISO-8601, got: " + value, e);
        }
    }
}
