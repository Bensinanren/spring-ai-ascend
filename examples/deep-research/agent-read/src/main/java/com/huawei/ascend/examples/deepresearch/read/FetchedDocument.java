package com.huawei.ascend.examples.deepresearch.read;

/**
 * Raw fetch result handed by a {@link DocumentReader} to the shared
 * {@link ReadUrlExtractor}. Carries the HTTP status (so the extractor can map
 * 403/429 -> {@code cloudflare_403} and 5xx -> {@code other}), the response
 * HTML (possibly empty), and a non-null {@code errorMessage} when the fetch
 * itself failed (network error, malformed URL, no fixture match).
 *
 * <p>{@code statusCode == 0} signals a non-HTTP failure (the prod reader could
 * not even get a response; the stub reader found no fixture route).
 */
public record FetchedDocument(String url, int statusCode, String html, String errorMessage) {

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isBlank();
    }
}
