package com.huawei.ascend.examples.deepresearch.read;

import java.util.Map;

/**
 * Stub {@code read_url} tool bridge. Wired from {@code agent.stub.yaml}.
 * Backed by {@link StubDocumentReader}; routes the URL host to an HTML fixture
 * on the classpath and never touches the network.
 *
 * <p>Same I/O contract as {@link ReadUrlTool} — only the YAML
 * {@code tools[].ref.class} differs between stub and prod, so the assembly
 * path is identical and there is no behavioural drift between profiles.
 */
public final class StubReadUrlTool {

    private static final DocumentReader PROVIDER = new StubDocumentReader();

    private StubReadUrlTool() {
    }

    public static Object read(Map<String, Object> inputs) {
        String url = stringInput(inputs, "url", "");
        String focusQuestion = normalizeFocus(stringInput(inputs, "focus_question", ""));

        if (url.isBlank()) {
            return ReadUrlResultSerializer.serialize(
                    ReadUrlExtractor.extract(new FetchedDocument("", 0, "", "url is required"), focusQuestion));
        }

        FetchedDocument doc = PROVIDER.fetch(url);
        return ReadUrlResultSerializer.serialize(ReadUrlExtractor.extract(doc, focusQuestion));
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
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }
}
