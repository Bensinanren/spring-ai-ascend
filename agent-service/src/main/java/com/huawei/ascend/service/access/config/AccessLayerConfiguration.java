package com.huawei.ascend.service.access.config;

import com.huawei.ascend.service.access.a2a.A2aAccessService;
import com.huawei.ascend.service.access.a2a.A2aAgentCard;
import com.huawei.ascend.service.access.a2a.A2aAgentCardAdapter;
import com.huawei.ascend.service.access.a2a.A2aAgentCardService;
import com.huawei.ascend.service.access.a2a.A2aAgentInterface;
import com.huawei.ascend.service.access.a2a.A2aEgressAdapter;
import com.huawei.ascend.service.access.a2a.A2aOutputRegistry;
import com.huawei.ascend.service.access.a2a.A2aIngressAdapter;
import com.huawei.ascend.service.access.a2a.A2aOutputSink;
import com.huawei.ascend.service.access.a2a.A2aPushNotificationClient;
import com.huawei.ascend.service.access.a2a.A2aReplyMode;
import com.huawei.ascend.service.access.a2a.DefaultA2aOutputSink;
import com.huawei.ascend.service.access.a2a.HttpA2aPushNotificationClient;
import com.huawei.ascend.service.access.egress.DefaultEgressQueueRegistry;
import com.huawei.ascend.service.access.egress.DefaultNotificationPort;
import com.huawei.ascend.service.access.egress.EgressAdapter;
import com.huawei.ascend.service.access.egress.EgressDispatcher;
import com.huawei.ascend.service.access.egress.EgressQueueRegistry;
import com.huawei.ascend.service.access.gateway.AccessGateway;
import com.huawei.ascend.service.access.mq.MqEgressAdapter;
import com.huawei.ascend.service.access.mq.MqIngressAdapter;
import com.huawei.ascend.service.access.mq.MqIngressQueue;
import com.huawei.ascend.service.access.mq.MqOutputSink;
import com.huawei.ascend.service.access.port.NotificationPort;
import com.huawei.ascend.service.access.port.TaskHandler;
import com.huawei.ascend.service.access.temp.L3QueuePlaceholders.InMemoryQueueFactory;
import com.huawei.ascend.service.access.temp.L3QueuePlaceholders.QueueFactory;
import com.huawei.ascend.service.access.temp.TemporaryL4TaskHandler;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AccessLayerConfiguration {

    @Bean
    @ConditionalOnMissingBean(TaskHandler.class)
    TaskHandler temporaryL4TaskHandler(NotificationPort notificationPort, Executor accessEgressExecutor) {
        return new TemporaryL4TaskHandler(notificationPort, accessEgressExecutor);
    }

    @Bean
    @ConditionalOnMissingBean(A2aAgentCard.class)
    A2aAgentCard a2aAgentCard() {
        return new A2aAgentCard(
                "spring-ai-ascend-agent",
                "Temporary A2A agent card for L1 access-layer integration.",
                "/a2a",
                "0.1.0",
                null,
                "spring-ai-ascend",
                List.of("text"),
                List.of("text", "artifact"),
                List.of(),
                List.of(new A2aAgentInterface("JSON-RPC", "/a2a", Map.of())),
                "JSON-RPC",
                Map.of(),
                List.of(),
                Map.of("replyModes", List.of(
                        A2aReplyMode.SYNC.name(),
                        A2aReplyMode.STREAM.name(),
                        A2aReplyMode.PUSH_NOTIFICATION.name())));
    }

    @Bean
    @ConditionalOnMissingBean
    A2aAgentCardService a2aAgentCardService(A2aAgentCard agentCard) {
        return new A2aAgentCardAdapter(agentCard);
    }

    @Bean
    @ConditionalOnMissingBean
    A2aOutputRegistry a2aOutputRegistry() {
        return new A2aOutputRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    A2aPushNotificationClient a2aPushNotificationClient(RestClient.Builder restClientBuilder) {
        return new HttpA2aPushNotificationClient(restClientBuilder);
    }

    @Bean
    @ConditionalOnMissingBean(A2aOutputSink.class)
    A2aOutputSink a2aOutputSink(
            A2aOutputRegistry outputRegistry,
            A2aPushNotificationClient pushNotificationClient) {
        return new DefaultA2aOutputSink(outputRegistry, pushNotificationClient);
    }

    @Bean
    @ConditionalOnMissingBean(QueueFactory.class)
    QueueFactory accessQueueFactory() {
        return new InMemoryQueueFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    EgressQueueRegistry egressQueueRegistry(QueueFactory queueFactory) {
        return new DefaultEgressQueueRegistry(queueFactory);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "accessEgressExecutor")
    ExecutorService accessEgressExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    @ConditionalOnMissingBean
    EgressDispatcher egressDispatcher(
            EgressQueueRegistry egressQueueRegistry,
            Collection<EgressAdapter> egressAdapters,
            Executor accessEgressExecutor) {
        return new EgressDispatcher(egressQueueRegistry, egressAdapters, accessEgressExecutor);
    }

    @Bean
    @ConditionalOnMissingBean(NotificationPort.class)
    NotificationPort notificationPort(EgressQueueRegistry egressQueueRegistry) {
        return new DefaultNotificationPort(egressQueueRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(A2aEgressAdapter.class)
    A2aEgressAdapter a2aEgressAdapter(A2aOutputSink outputSink) {
        return new A2aEgressAdapter(outputSink);
    }

    @Bean
    @ConditionalOnBean(MqOutputSink.class)
    @ConditionalOnMissingBean(MqEgressAdapter.class)
    MqEgressAdapter mqEgressAdapter(MqOutputSink outputSink) {
        return new MqEgressAdapter(outputSink);
    }

    @Bean
    @ConditionalOnBean(TaskHandler.class)
    @ConditionalOnMissingBean
    AccessGateway accessGateway(
            TaskHandler taskHandler,
            EgressQueueRegistry egressQueueRegistry,
            EgressDispatcher egressDispatcher) {
        return new AccessGateway(taskHandler, egressQueueRegistry, egressDispatcher);
    }

    @Bean
    @ConditionalOnBean(AccessGateway.class)
    @ConditionalOnMissingBean(A2aAccessService.class)
    A2aAccessService a2aAccessService(AccessGateway accessGateway) {
        return new A2aIngressAdapter(accessGateway);
    }

    @Bean
    @ConditionalOnBean(AccessGateway.class)
    @ConditionalOnMissingBean(MqIngressQueue.class)
    MqIngressQueue mqIngressQueue(AccessGateway accessGateway) {
        return new MqIngressAdapter(accessGateway);
    }
}
