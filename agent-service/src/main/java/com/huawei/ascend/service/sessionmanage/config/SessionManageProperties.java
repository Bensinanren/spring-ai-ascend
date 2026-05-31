package com.huawei.ascend.service.sessionmanage.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-service.session")
public record SessionManageProperties(Duration ttl) {

    public SessionManageProperties {
        ttl = ttl == null ? Duration.ofHours(24) : ttl;
    }
}
