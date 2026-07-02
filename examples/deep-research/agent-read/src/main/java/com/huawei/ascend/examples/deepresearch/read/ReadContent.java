package com.huawei.ascend.examples.deepresearch.read;

import java.util.List;

/**
 * Extracted article payload, prior to wire serialization. Fields line up with
 * the TOPOLOGY §3.3 output contract:
 * <pre>{@code
 * { title, content_markdown, sections[], summary, metadata{...} }
 * }</pre>
 * {@code summary} is an extractive fallback produced by the tool; the ReAct
 * agent (LLM) is expected to refine it when {@code focus_question} is set.
 */
public record ReadContent(
        String title,
        String contentMarkdown,
        List<ReadSection> sections,
        String summary,
        ReadMetadata metadata) {
}
