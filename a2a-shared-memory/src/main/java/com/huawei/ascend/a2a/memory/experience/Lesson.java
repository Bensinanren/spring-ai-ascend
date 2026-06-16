package com.huawei.ascend.a2a.memory.experience;

/**
 * One distilled, PII-stripped piece of cross-run collaboration experience.
 *
 * @param text          the lesson (already redacted before it reaches the store)
 * @param sourceAgentId which agent's conclusion it came from (may be null)
 * @param tsEpochMs     when it was recorded
 */
public record Lesson(String text, String sourceAgentId, long tsEpochMs) {
}
