package com.huawei.ascend.service.sessionmanage.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-service.session.store.redis")
public record RedisSessionStateProperties(
        String keyPrefix,
        Duration ttl,
        int maxCasRetries) {

    private static final int DEFAULT_MAX_CAS_RETRIES = 16;

    public RedisSessionStateProperties {
        keyPrefix = keyPrefix == null || keyPrefix.isBlank()
                ? "spring-ai-ascend:session:"
                : keyPrefix;
        ttl = ttl == null ? Duration.ofHours(24) : ttl;
        maxCasRetries = maxCasRetries <= 0 ? DEFAULT_MAX_CAS_RETRIES : maxCasRetries;
    }
}
