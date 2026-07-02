package com.huawei.ascend.examples.deepresearch.read;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@link ReadContent} into the wire shape declared in TOPOLOGY §3.3:
 * <pre>{@code
 * {
 *   "title": "...",
 *   "content_markdown": "...",
 *   "sections": [ { "heading": "...", "body": "..." } ],
 *   "summary": "...",
 *   "metadata": { "author": "...|null", "publish_date": "...|null", "doc_type": "..." }
 * }
 * }</pre>
 * Centralised so the prod and stub tool bridges emit byte-identical output.
 */
final class ReadUrlResultSerializer {

    private ReadUrlResultSerializer() {
    }

    static Map<String, Object> serialize(ReadContent content) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", nullToEmpty(content.title()));
        out.put("content_markdown", nullToEmpty(content.contentMarkdown()));

        List<Map<String, Object>> sections = new ArrayList<>();
        if (content.sections() != null) {
            for (ReadSection s : content.sections()) {
                Map<String, Object> sec = new LinkedHashMap<>();
                sec.put("heading", nullToEmpty(s.heading()));
                sec.put("body", nullToEmpty(s.body()));
                sections.add(sec);
            }
        }
        out.put("sections", sections);
        out.put("summary", nullToEmpty(content.summary()));

        Map<String, Object> meta = new LinkedHashMap<>();
        ReadMetadata m = content.metadata();
        meta.put("author", m == null ? null : m.author());
        meta.put("publish_date", m == null ? null : m.publishDate());
        meta.put("doc_type", m == null ? DocType.OTHER.wireName() : m.docType().wireName());
        out.put("metadata", meta);
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
