package com.huawei.ascend.runtime.engine.agentscope;

import com.huawei.ascend.runtime.engine.spi.ErrorCategory;

/**
 * Maps AgentScope error code strings to the adapter-neutral {@link ErrorCategory}.
 *
 * <p>Actual codes produced by this adapter:
 * <ul>
 *   <li>{@code AGENTSCOPE_RUNTIME_IO} — network / socket failure
 *   <li>{@code AGENTSCOPE_RUNTIME_HTTP_<status>} — HTTP-level error; status suffix is the numeric
 *       HTTP response code (e.g. {@code AGENTSCOPE_RUNTIME_HTTP_429})
 *   <li>{@code AGENTSCOPE_RUNTIME_PARSE} — response body could not be parsed
 *   <li>{@code AGENTSCOPE_ERROR} — generic/unclassified error (default when no code supplied)
 * </ul>
 */
final class AgentScopeErrorCategories {

    private static final String HTTP_PREFIX = "AGENTSCOPE_RUNTIME_HTTP_";

    private AgentScopeErrorCategories() { }

    static ErrorCategory categorize(String code) {
        if (code == null || code.isBlank()) {
            return ErrorCategory.UNKNOWN;
        }
        if (code.contains("RUNTIME_IO")) {
            return ErrorCategory.CONNECTION_ERROR;
        }
        if (code.contains("RUNTIME_PARSE")) {
            return ErrorCategory.PARSE_ERROR;
        }
        if (code.startsWith(HTTP_PREFIX)) {
            return categorizeHttpCode(code.substring(HTTP_PREFIX.length()));
        }
        return ErrorCategory.UNKNOWN;
    }

    private static ErrorCategory categorizeHttpCode(String statusStr) {
        try {
            int status = Integer.parseInt(statusStr);
            if (status == 401 || status == 403) {
                return ErrorCategory.INVALID_API_KEY;
            }
            if (status == 429) {
                return ErrorCategory.RATE_LIMITED;
            }
            if (status >= 400 && status < 500) {
                return ErrorCategory.INVALID_REQUEST;
            }
            if (status >= 500) {
                return ErrorCategory.SERVER_ERROR;
            }
        } catch (NumberFormatException ignored) {
            // fall through to SERVER_ERROR for unrecognised suffix
        }
        return ErrorCategory.SERVER_ERROR;
    }
}
