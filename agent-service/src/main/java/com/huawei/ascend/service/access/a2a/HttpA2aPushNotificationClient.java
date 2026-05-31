package com.huawei.ascend.service.access.a2a;

import java.net.URI;
import java.util.Objects;
import org.springframework.web.client.RestClient;

public final class HttpA2aPushNotificationClient implements A2aPushNotificationClient {

    private final RestClient restClient;

    public HttpA2aPushNotificationClient(RestClient.Builder restClientBuilder) {
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder").build();
    }

    @Override
    public void push(String target, A2aOutput output) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("A2A push target must not be blank");
        }
        restClient.post()
                .uri(URI.create(target))
                .body(output)
                .retrieve()
                .toBodilessEntity();
    }
}
