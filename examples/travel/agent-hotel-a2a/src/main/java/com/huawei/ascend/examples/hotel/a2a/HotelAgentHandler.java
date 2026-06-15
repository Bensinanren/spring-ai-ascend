/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.examples.hotel.a2a;

import com.huawei.ascend.examples.hotel.HotelPlanningAgent;
import com.huawei.ascend.runtime.engine.AgentExecutionContext;
import com.huawei.ascend.runtime.engine.spi.AgentExecutionResult;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.MemoryProvider;
import com.huawei.ascend.runtime.engine.spi.MemoryProvider.MemoryHit;
import com.huawei.ascend.runtime.engine.spi.MemoryProvider.MemoryRecord;
import com.huawei.ascend.runtime.engine.spi.StreamAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain SPI handler for the hotel sub-agent that wires memory recall by hand.
 *
 * <p>{@link com.huawei.ascend.runtime.engine.openjiuwen.OpenJiuwenAgentRuntimeHandler}
 * registers a memory rail that adds a {@code runtime_long_term_memory} prompt
 * section in its {@code beforeInvoke} callback, but empirically that section
 * never surfaces in the assembled system message — the same
 * {@code addPromptBuilderSection} API works fine for sections registered
 * synchronously at agent construction (see
 * {@link HotelPlanningAgent#newBaseAgent()} registering
 * {@code hotel_business_rules}). Suspected root cause is the dual
 * {@code SystemPromptBuilder} fields on the upstream openJiuwen ReActAgent
 * (writer touches one, renderer reads the other). Tracked as a follow-up to
 * agent-runtime PR #248.
 *
 * <p>Until that lands we do recall in the handler: search before
 * {@link HotelPlanningAgent#chat(String)}, prepend a {@code Relevant memory:}
 * block to the user query, and persist the original user/assistant turn
 * after. {@code SystemPromptBuilder} rule 8 already instructs the LLM to
 * treat such a block as recallable history.
 */
final class HotelAgentHandler implements AgentRuntimeHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HotelAgentHandler.class);
    private static final StreamAdapter ADAPTER = rawResults -> rawResults.map(AgentExecutionResult.class::cast);

    private static final String RECALL_OPEN = "Relevant memory:\n";
    private static final String RECALL_CLOSE = "\n---\n";
    private static final int RECALL_LIMIT = 6;

    private final String agentId;
    private final HotelPlanningAgent agent;
    private final MemoryProvider memoryProvider;

    HotelAgentHandler(String agentId, HotelPlanningAgent agent, MemoryProvider memoryProvider) {
        this.agentId = agentId;
        this.agent = Objects.requireNonNull(agent, "agent");
        this.memoryProvider = Objects.requireNonNull(memoryProvider, "memoryProvider");
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public Stream<?> execute(AgentExecutionContext context) {
        String query = context.lastUserText();
        LOGGER.info("hotel a2a execute tenantId={} userId={} sessionId={} taskId={} queryLength={}",
                context.getScope().tenantId(),
                context.getScope().userId(),
                context.getScope().sessionId(),
                context.getScope().taskId(),
                query.length());
        try {
            memoryProvider.init(context);
            String enrichedQuery = prependRecall(context, query);
            String markdown = agent.chat(enrichedQuery);
            saveTurn(context, query, markdown);
            return Stream.of(AgentExecutionResult.completed(markdown));
        } catch (Exception e) {
            LOGGER.warn("hotel a2a execute failed tenantId={} userId={} taskId={} errorClass={} message={}",
                    context.getScope().tenantId(),
                    context.getScope().userId(),
                    context.getScope().taskId(),
                    e.getClass().getSimpleName(),
                    errorMessage(e));
            throw new IllegalStateException(errorMessage(e), e);
        }
    }

    @Override
    public StreamAdapter resultAdapter() {
        return ADAPTER;
    }

    private String prependRecall(AgentExecutionContext context, String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        List<MemoryHit> hits = memoryProvider.search(context, query, RECALL_LIMIT);
        if (hits == null || hits.isEmpty()) {
            return query;
        }
        StringBuilder block = new StringBuilder(RECALL_OPEN);
        int written = 0;
        for (MemoryHit hit : hits) {
            String content = hit == null ? null : hit.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            block.append("- ").append(content.replace("\n", " ").trim()).append('\n');
            written++;
        }
        if (written == 0) {
            return query;
        }
        block.append(RECALL_CLOSE);
        LOGGER.info("hotel a2a recall written={} previewLen={}", written, block.length());
        return block + query;
    }

    private void saveTurn(AgentExecutionContext context, String originalQuery, String assistantReply) {
        List<MemoryRecord> records = new ArrayList<>(2);
        if (originalQuery != null && !originalQuery.isBlank()) {
            records.add(new MemoryRecord(UUID.randomUUID().toString(), "user", originalQuery, Map.of()));
        }
        if (assistantReply != null && !assistantReply.isBlank()) {
            records.add(new MemoryRecord(UUID.randomUUID().toString(), "assistant", assistantReply, Map.of()));
        }
        if (!records.isEmpty()) {
            memoryProvider.save(context, records);
        }
    }

    private static String errorMessage(Throwable error) {
        StringBuilder message = new StringBuilder();
        Throwable cursor = error;
        while (cursor != null) {
            String part = cursor.getMessage();
            if (part != null && !part.isBlank()) {
                if (!message.isEmpty()) {
                    message.append(": ");
                }
                message.append(part);
            }
            cursor = cursor.getCause();
        }
        return message.isEmpty() ? error.getClass().getName() : message.toString();
    }
}
