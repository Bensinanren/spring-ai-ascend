package com.huawei.ascend.examples.deepresearch.read;

/**
 * Pluggable fetch backend behind the {@code read_url} skill — see TOPOLOGY §3.3
 * ("抽象 {@code DocumentReader} interface，PDF / 内部知识库后续阶段加新实现类").
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link HtmlDocumentReader} — prod: JDK {@code HttpClient} GET against
 *       the public web, returns status + raw HTML.</li>
 *   <li>{@link StubDocumentReader} — stub: routes the URL host+path to an HTML
 *       fixture on the classpath, returns a simulated status, never touches the
 *       network.</li>
 * </ul>
 * Both feed the same {@link ReadUrlExtractor}, so prod and stub exercise an
 * identical extraction pipeline (no behavioural drift between profiles).
 */
public interface DocumentReader {

    FetchedDocument fetch(String url);
}
