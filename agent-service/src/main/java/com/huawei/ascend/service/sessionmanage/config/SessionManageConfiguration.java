package com.huawei.ascend.service.sessionmanage.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.service.sessionmanage.SessionManager;
import com.huawei.ascend.service.sessionmanage.SessionManagerImpl;
import com.huawei.ascend.service.sessionmanage.memory.InMemorySessionStateAdapter;
import com.huawei.ascend.service.sessionmanage.redis.JacksonSessionCodec;
import com.huawei.ascend.service.sessionmanage.redis.RedisSessionCommands;
import com.huawei.ascend.service.sessionmanage.redis.RedisSessionStateAdapter;
import com.huawei.ascend.service.sessionmanage.redis.RedisSessionStateProperties;
import com.huawei.ascend.service.sessionmanage.redis.SessionCodec;
import com.huawei.ascend.service.sessionmanage.spi.SessionStatePort;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SessionManageProperties.class, RedisSessionStateProperties.class})
public class SessionManageConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock sessionClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(SessionCodec.class)
    SessionCodec sessionCodec(ObjectMapper objectMapper) {
        return new JacksonSessionCodec(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent-service.session.store", name = "type", havingValue = "redis")
    @ConditionalOnBean(RedisSessionCommands.class)
    @ConditionalOnMissingBean(SessionStatePort.class)
    SessionStatePort redisSessionStatePort(
            RedisSessionCommands commands,
            SessionCodec codec,
            RedisSessionStateProperties properties) {
        return new RedisSessionStateAdapter(commands, codec, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent-service.session.store", name = "type", havingValue = "memory", matchIfMissing = true)
    @ConditionalOnMissingBean(SessionStatePort.class)
    SessionStatePort inMemorySessionStatePort() {
        return new InMemorySessionStateAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    SessionManager sessionManager(
            SessionStatePort sessionStatePort,
            Clock sessionClock,
            SessionManageProperties properties) {
        return new SessionManagerImpl(sessionStatePort, sessionClock, properties.ttl());
    }
}
