package com.huawei.ascend.examples.deepresearch.read;

import java.util.Map;

/**
 * Prod {@code read_url} tool bridge. Wired from {@code agent.prod.yaml} via
 * {@code tools[].ref.{class,method}}. Holds a lazily-initialised
 * {@link HtmlDocumentReader} (no external API key required — only outbound
 * HTTP, which honours {@code -Dhttps.proxyHost}).
 *
 * <p>YAML constraint: must expose
 * {@code public static Object read(Map<String,Object>)}. The same static
 * method is invoked by every ReAct iteration.
 *
 * <p>Stub counterpart: {@link StubReadUrlTool}.
 */
public final class ReadUrlTool {

    private static volatile DocumentReader provider;

    private ReadUrlTool() {
    }

    public static Object read(Map<String, Object> inputs) {
        String url = stringInput(inputs, "url", "");
        String focusQuestion = normalizeFocus(stringInput(inputs, "focus_question", ""));

        if (url.isBlank()) {
            return ReadUrlResultSerializer.serialize(
                    ReadUrlExtractor.extract(new FetchedDocument("", 0, "", "url is required"), focusQuestion));
        }

        DocumentReader reader = provider();
        FetchedDocument doc;
        try {
            doc = reader.fetch(url);
        } catch (RuntimeException ex) {
            doc = new FetchedDocument(url, 0, "", "read_failed: " + ex.getMessage());
        }
        return ReadUrlResultSerializer.serialize(ReadUrlExtractor.extract(doc, focusQuestion));
    }

    /** Visible for tests — override the reader with a WireMock-backed client. */
    static void useDocumentReader(DocumentReader override) {
        provider = override;
    }

    private static DocumentReader provider() {
        DocumentReader local = provider;
        if (local != null) {
            return local;
        }
        synchronized (ReadUrlTool.class) {
            if (provider == null) {
                provider = new HtmlDocumentReader();
            }
            return provider;
        }
    }

    private static String stringInput(Map<String, Object> inputs, String key, String fallback) {
        Object v = inputs == null ? null : inputs.get(key);
        return v == null ? fallback : v.toString();
    }

    private static String normalizeFocus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        // Tolerate a literal JSON null leaking through as the focus_question value.
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }
}
