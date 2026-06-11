package com.huawei.ascend.examples.a2a;

import com.huawei.ascend.client.AscendA2aClient;
import com.huawei.ascend.client.SendSpec;
import com.huawei.ascend.client.telemetry.ClientTelemetry;
import com.huawei.ascend.client.telemetry.Posture;
import java.time.Duration;
import java.util.Locale;
import java.util.Scanner;
import java.util.UUID;

public final class A2aConsoleClientApplication {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final String DEFAULT_AGENT_ID = "openjiuwen-react-agent";
    private static final String DEFAULT_USER_ID = "manual-user";

    private A2aConsoleClientApplication() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = value(args, 0, "SAA_SAMPLE_A2A_BASE_URL", DEFAULT_BASE_URL);
        String agentId = value(args, 1, "SAA_SAMPLE_AGENT_ID", DEFAULT_AGENT_ID);
        String userId = value(args, 2, "SAA_SAMPLE_USER_ID", DEFAULT_USER_ID);
        String sessionId = "manual-session-" + UUID.randomUUID();

        // Closing the client also closes the telemetry, which flushes an
        // owned OTLP pipeline — the try-with-resources below is the flush.
        try (AscendA2aClient client = AscendA2aClient.builder()
                .baseUrl(baseUrl)
                .timeout(TIMEOUT)
                .telemetry(telemetryFromEnv())
                .build();
                Scanner scanner = new Scanner(System.in)) {
            System.out.println("Connected to " + client.agentCard().name() + " at " + baseUrl);
            System.out.println("Type a message and press Enter. Type exit to quit.");
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    return;
                }
                String input = scanner.nextLine().trim();
                if (input.isBlank()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                    return;
                }
                String answer = client.streamText(
                        SendSpec.of(agentId, sessionId, userId, input)).text();
                System.out.println(answer.isBlank() ? "(empty response)" : answer);
            }
        }
    }

    /**
     * Optional OTLP telemetry: when {@code SAA_SAMPLE_OTLP_ENDPOINT} is set,
     * the client owns a {@link ClientTelemetry#otlpHttp} pipeline exporting
     * to it, at the posture named by {@code SAA_SAMPLE_TELEMETRY_POSTURE}
     * (DEV/RESEARCH/PROD, default DEV). Unset means noop — unchanged behavior.
     */
    private static ClientTelemetry telemetryFromEnv() {
        String endpoint = System.getenv("SAA_SAMPLE_OTLP_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            return ClientTelemetry.noop();
        }
        String posture = System.getenv("SAA_SAMPLE_TELEMETRY_POSTURE");
        return ClientTelemetry.otlpHttp(endpoint,
                posture == null || posture.isBlank()
                        ? Posture.DEV
                        : Posture.valueOf(posture.trim().toUpperCase(Locale.ROOT)),
                null);
    }

    private static String value(String[] args, int index, String envName, String defaultValue) {
        if (args.length > index && !args[index].isBlank()) {
            return args[index];
        }
        String envValue = System.getenv(envName);
        return envValue == null || envValue.isBlank() ? defaultValue : envValue;
    }
}
