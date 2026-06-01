/*
package com.huawei.ascend.service.access.protocol.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.ascend.service.access.temp.TemporaryA2aApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = TemporaryA2aApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class A2aStreamingMessageModeTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void sendStreamingMessageReturnsSseEvents() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/a2a/"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                A2aSendMessageModeTest.requestBody("SendStreamingMessage")
                                        .replace("req-send", "req-stream")
                                        .replace("msg-send", "msg-stream")
                                        .replace("session-send", "session-stream")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        assertThat(response.body()).contains("event:jsonrpc");
        assertThat(response.body()).contains("\"id\":\"req-stream\"");
        assertThat(response.body()).contains("temporary task accepted");
        assertThat(response.body()).contains("temporary streaming result for hello from send mode");
    }
}
*/
