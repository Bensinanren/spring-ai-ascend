package com.huawei.ascend.agentsdk.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.agentsdk.spec.tool.McpExecutionHandle;
import com.huawei.ascend.agentsdk.spec.tool.McpServerSpec;
import com.huawei.ascend.agentsdk.support.ToolExecutionException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpToolExecutorTest {

    private static final McpServerSpec INVENTORY = new McpServerSpec(
            "inventory", "inventory-server", List.of(), Map.of(), null, Map.of());
    private static final McpExecutionHandle LOOKUP = new McpExecutionHandle("inventory", "lookup");

    @Test
    void forwardsToolNameAndInputsAndReturnsSingleTextContentAsString() {
        StubConnection connection = new StubConnection(textResult("3 units in stock"));
        McpToolExecutor executor = executor(connection);

        Object result = executor.execute(LOOKUP, Map.of("sku", "s-1"));

        assertThat(result).isEqualTo("3 units in stock");
        assertThat(connection.requests).hasSize(1);
        assertThat(connection.requests.get(0).name()).isEqualTo("lookup");
        assertThat(connection.requests.get(0).arguments()).containsEntry("sku", "s-1");
    }

    @Test
    void structuredContentWinsOverTextAndDecodesToMap() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ignored rendering")),
                false,
                Map.of("sku", "s-1", "count", 3),
                null);
        McpToolExecutor executor = executor(new StubConnection(result));

        Object value = executor.execute(LOOKUP, Map.of());

        assertThat(value).isInstanceOf(Map.class);
        Map<?, ?> decoded = (Map<?, ?>) value;
        assertThat(decoded.get("sku")).isEqualTo("s-1");
        assertThat(decoded.get("count")).isEqualTo(3);
    }

    @Test
    void multipleContentItemsReturnAsList() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("first"), new McpSchema.TextContent("second")),
                false,
                null,
                null);
        McpToolExecutor executor = executor(new StubConnection(result));

        Object value = executor.execute(LOOKUP, Map.of());

        assertThat(value).isEqualTo(List.of("first", "second"));
    }

    @Test
    void isErrorResultFailsLoudlyWithServerToolAndErrorText() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("sku not found")), true, null, null);
        McpToolExecutor executor = executor(new StubConnection(result));

        assertThatThrownBy(() -> executor.execute(LOOKUP, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("lookup")
                .hasMessageContaining("inventory")
                .hasMessageContaining("sku not found");
    }

    @Test
    void transportFailureWrapsIntoToolExecutionExceptionWithServerAndTool() {
        StubConnection connection = new StubConnection(textResult("unused"));
        connection.failure = new IllegalStateException("pipe broke");
        McpToolExecutor executor = executor(connection);

        assertThatThrownBy(() -> executor.execute(LOOKUP, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("lookup")
                .hasMessageContaining("inventory")
                .hasMessageContaining("pipe broke");
    }

    @Test
    void unknownServerFailsWithTheServerName() {
        McpToolExecutor executor = new McpToolExecutor(Map.of(), spec -> new StubConnection(textResult("x")));

        assertThatThrownBy(() -> executor.execute(new McpExecutionHandle("ghost", "lookup"), Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("mcpServers");
    }

    @Test
    void oneConnectionPerServerIsCachedAndCloseTearsItDown() {
        AtomicInteger connects = new AtomicInteger();
        StubConnection connection = new StubConnection(textResult("ok"));
        McpToolExecutor executor = new McpToolExecutor(Map.of("inventory", INVENTORY), spec -> {
            connects.incrementAndGet();
            return connection;
        });

        executor.execute(LOOKUP, Map.of());
        executor.execute(LOOKUP, Map.of());
        assertThat(connects).hasValue(1);

        executor.close();
        assertThat(connection.closed).isTrue();

        executor.execute(LOOKUP, Map.of());
        assertThat(connects).hasValue(2);
    }

    @Test
    void deadConnectionIsEvictedAndTheCallRetriesOnceOnAFreshOne() {
        StubConnection dead = new StubConnection(textResult("unused"));
        dead.failure = new IllegalStateException("stdio process exited");
        StubConnection fresh = new StubConnection(textResult("recovered"));
        List<StubConnection> sequence = new ArrayList<>(List.of(dead, fresh));
        AtomicInteger connects = new AtomicInteger();
        McpToolExecutor executor = new McpToolExecutor(Map.of("inventory", INVENTORY), spec -> {
            connects.incrementAndGet();
            return sequence.remove(0);
        });

        Object result = executor.execute(LOOKUP, Map.of());

        assertThat(result).isEqualTo("recovered");
        assertThat(connects).hasValue(2);
        assertThat(dead.closed).isTrue();
        assertThat(fresh.requests).hasSize(1);
    }

    @Test
    void failureAfterTheSingleReconnectEvictsAgainSoTheNextCallStartsClean() {
        AtomicInteger connects = new AtomicInteger();
        StubConnection healthy = new StubConnection(textResult("back online"));
        McpToolExecutor executor = new McpToolExecutor(Map.of("inventory", INVENTORY), spec -> {
            int attempt = connects.incrementAndGet();
            if (attempt <= 2) {
                StubConnection broken = new StubConnection(textResult("unused"));
                broken.failure = new IllegalStateException("sse session dropped");
                return broken;
            }
            return healthy;
        });

        assertThatThrownBy(() -> executor.execute(LOOKUP, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("sse session dropped");
        // Exactly one reconnect attempt inside the failing call — no retry loop.
        assertThat(connects).hasValue(2);

        // The broken connection was evicted, so the next call connects afresh and succeeds.
        assertThat(executor.execute(LOOKUP, Map.of())).isEqualTo("back online");
        assertThat(connects).hasValue(3);
    }

    @Test
    void connectionFailureSurfacesAsToolExecutionException() {
        McpToolExecutor executor = new McpToolExecutor(Map.of("inventory", INVENTORY), spec -> {
            throw new IllegalStateException("spawn refused");
        });

        assertThatThrownBy(() -> executor.execute(LOOKUP, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("inventory")
                .hasMessageContaining("spawn refused");
    }

    private static McpToolExecutor executor(StubConnection connection) {
        return new McpToolExecutor(Map.of("inventory", INVENTORY), spec -> connection);
    }

    private static McpSchema.CallToolResult textResult(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false, null, null);
    }

    private static final class StubConnection implements McpToolExecutor.McpConnection {
        final List<McpSchema.CallToolRequest> requests = new ArrayList<>();
        final McpSchema.CallToolResult result;
        RuntimeException failure;
        boolean closed;

        StubConnection(McpSchema.CallToolResult result) {
            this.result = result;
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
