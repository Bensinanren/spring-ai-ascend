package com.huawei.ascend.agentsdk.example.rails;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExampleRailHooks {
    private static final AtomicInteger AFTER_MODEL_CALLS = new AtomicInteger();
    private static final AtomicInteger AFTER_TOOL_CALLS = new AtomicInteger();

    private ExampleRailHooks() {
    }

    public static void afterModelCall(AgentCallbackContext context) {
        int count = AFTER_MODEL_CALLS.incrementAndGet();
        System.out.println("[agent-sdk-example] rail afterModelCall invoked, count=" + count
                + ", event=" + context.getEvent());
    }

    public static void afterToolCall(AgentCallbackContext context) {
        int count = AFTER_TOOL_CALLS.incrementAndGet();
        System.out.println("[agent-sdk-example] rail afterToolCall invoked, count=" + count
                + ", event=" + context.getEvent());
    }

    public static int afterModelCallCount() {
        return AFTER_MODEL_CALLS.get();
    }

    public static int afterToolCallCount() {
        return AFTER_TOOL_CALLS.get();
    }

    public static void reset() {
        AFTER_MODEL_CALLS.set(0);
        AFTER_TOOL_CALLS.set(0);
    }
}
