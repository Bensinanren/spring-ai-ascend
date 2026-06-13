package com.huawei.ascend.runtime.engine.spi;

/**
 * Finite set of error categories aligned to the OpenTelemetry {@code gen_ai.error.type}
 * attribute. Alerting and SLO rules key on the wire value rather than the free-text
 * {@code code} string so that classifier logic stays framework-neutral.
 *
 * <p>Per-adapter Throwable→category mapping is a separate concern: callers that do not
 * yet know the right category should pass {@link #UNKNOWN}; the category can be promoted
 * to a more specific constant as adapter coverage is added.
 */
public enum ErrorCategory {

    INVALID_REQUEST("invalid_request"),
    INVALID_API_KEY("invalid_api_key"),
    RATE_LIMITED("rate_limited"),
    CONTENT_FILTER("content_filter"),
    SERVER_ERROR("server_error"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled"),
    CONNECTION_ERROR("connection_error"),
    PARSE_ERROR("parse_error"),
    UNKNOWN("unknown");

    private final String wireValue;

    ErrorCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The lowercase OTel {@code gen_ai.error.type} wire string for this category. */
    public String wireValue() {
        return wireValue;
    }
}
