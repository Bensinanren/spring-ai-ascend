package com.huawei.ascend.runtime.engine.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCategoryTest {

    @Test
    void wireValuesMatchOtelGenAiErrorType() {
        assertThat(ErrorCategory.INVALID_REQUEST.wireValue()).isEqualTo("invalid_request");
        assertThat(ErrorCategory.INVALID_API_KEY.wireValue()).isEqualTo("invalid_api_key");
        assertThat(ErrorCategory.RATE_LIMITED.wireValue()).isEqualTo("rate_limited");
        assertThat(ErrorCategory.CONTENT_FILTER.wireValue()).isEqualTo("content_filter");
        assertThat(ErrorCategory.SERVER_ERROR.wireValue()).isEqualTo("server_error");
        assertThat(ErrorCategory.TIMEOUT.wireValue()).isEqualTo("timeout");
        assertThat(ErrorCategory.CANCELLED.wireValue()).isEqualTo("cancelled");
        assertThat(ErrorCategory.CONNECTION_ERROR.wireValue()).isEqualTo("connection_error");
        assertThat(ErrorCategory.PARSE_ERROR.wireValue()).isEqualTo("parse_error");
        assertThat(ErrorCategory.UNKNOWN.wireValue()).isEqualTo("unknown");
    }

    @Test
    void allTenConstantsPresent() {
        assertThat(ErrorCategory.values()).hasSize(10);
    }
}
