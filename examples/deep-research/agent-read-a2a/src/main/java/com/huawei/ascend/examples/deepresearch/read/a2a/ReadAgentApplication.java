package com.huawei.ascend.examples.deepresearch.read.a2a;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the read sub-agent A2A wrapper. agent-runtime auto-
 * configures the A2A JSON-RPC endpoint from the {@code agent-runtime.access.a2a}
 * properties in {@code application.yaml}; this class only contributes the
 * handler / yaml-path / skill beans (see {@link ReadAgentConfiguration}).
 */
@SpringBootApplication
public class ReadAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadAgentApplication.class, args);
    }
}
