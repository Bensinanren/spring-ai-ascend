package com.huawei.ascend.examples.deepresearch.read;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Prod {@link DocumentReader}. Fetches public HTML via the JDK
 * {@code HttpClient} (HTTP/1.1) — no third-party HTTP library, per TOPOLOGY
 * §3.3. Returns the raw status + body so the shared {@link ReadUrlExtractor}
 * can map 403/429 -> {@code cloudflare_403} and 5xx -> {@code other}.
 *
 * <p>The corporate-proxy handling mirrors {@code TavilyWebSearchProvider}:
 * {@code ProxySelector.getDefault()} makes the same jar work direct-to-
 * internet locally and via {@code -Dhttps.proxyHost} on locked-down hosts.
 * A desktop browser User-Agent is sent because most LLM-vendor pricing pages
 * 403 the default Java HttpClient UA.
 */
public final class HtmlDocumentReader implements DocumentReader {

    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36 deep-research-read-agent/0.1";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public HtmlDocumentReader() {
        this(defaultHttpClient());
    }

    /** Visible for tests — inject a client pointed at a WireMock server. */
    HtmlDocumentReader(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public FetchedDocument fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return new FetchedDocument(url, 0, "", "invalid url: " + ex.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return new FetchedDocument(url, 0, "", "unsupported scheme (only http/https): " + scheme);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String body = decodeBody(response.body(), response.headers());
            return new FetchedDocument(url, status, body, null);
        } catch (IOException ex) {
            return new FetchedDocument(url, 0, "", "fetch failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new FetchedDocument(url, 0, "", "fetch interrupted");
        }
    }

    private static String decodeBody(byte[] bytes, HttpHeaders headers) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        // Resolve to a Charset (not a charset-name String) so the String(byte[],
        // Charset) ctor is used — it throws no checked exception, unlike the
        // String(byte[], String) overload which declares UnsupportedEncodingException.
        Charset charset = headers.firstValue("Content-Type")
                .map(HtmlDocumentReader::parseCharset)
                .map(HtmlDocumentReader::toCharset)
                .orElse(StandardCharsets.UTF_8);
        return new String(bytes, charset);
    }

    private static Charset toCharset(String name) {
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String parseCharset(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                return p.substring(8).trim();
            }
        }
        return null;
    }
}
