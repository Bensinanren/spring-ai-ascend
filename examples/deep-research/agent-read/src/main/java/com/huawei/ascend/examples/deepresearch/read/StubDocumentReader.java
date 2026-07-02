package com.huawei.ascend.examples.deepresearch.read;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Fixture-backed {@link DocumentReader} used by the {@code stub} Spring profile
 * (TOPOLOGY §4.1). Routes the URL host to an HTML fixture under
 * {@code /fixtures/*.html} on the classpath and returns a simulated HTTP status
 * — it never touches the network. Unmatched hosts return a failed
 * {@link FetchedDocument} so the extractor emits {@code doc_type=other}.
 *
 * <p>The stub routes the {@code cloudflare}/{@code blocked} host to a 403
 * status (the prod reader observes the real 403); the {@code spa} host serves
 * a minimal HTML page whose extracted text falls below the SPA threshold, so
 * the same {@link ReadUrlExtractor} SPA-detection path fires as in prod.
 */
public final class StubDocumentReader implements DocumentReader {

    private static final Logger LOG = Logger.getLogger(StubDocumentReader.class.getName());

    private final List<StubRoute> routes;

    private record StubRoute(String hostContains, String fixture, int statusCode) {
    }

    public StubDocumentReader() {
        this.routes = List.of(
                new StubRoute("volcengine", "/fixtures/pricing-volcengine.html", 200),
                new StubRoute("aliyun", "/fixtures/pricing-bailian.html", 200),
                new StubRoute("bailian", "/fixtures/pricing-bailian.html", 200),
                new StubRoute("csdn", "/fixtures/blog-comparison.html", 200),
                new StubRoute("juejin", "/fixtures/blog-comparison.html", 200),
                new StubRoute("zhihu", "/fixtures/blog-comparison.html", 200),
                new StubRoute("blog", "/fixtures/blog-comparison.html", 200),
                new StubRoute("spa", "/fixtures/spa-blocked.html", 200),
                new StubRoute("cloudflare", "/fixtures/cloudflare-403.html", 403),
                new StubRoute("blocked", "/fixtures/cloudflare-403.html", 403));
    }

    @Override
    public FetchedDocument fetch(String url) {
        String host = hostOf(url);
        if (host == null) {
            return miss(url, "cannot parse host");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        for (StubRoute route : routes) {
            if (lowerHost.contains(route.hostContains)) {
                String html = readFixture(route.fixture);
                if (html == null) {
                    return miss(url, "fixture missing on classpath: " + route.fixture);
                }
                return new FetchedDocument(url, route.statusCode, html, null);
            }
        }
        return miss(url, "no fixture route for host " + host);
    }

    private static FetchedDocument miss(String url, String reason) {
        LOG.warning(() -> "stub read fixture miss for url=\"" + url + "\": " + reason);
        return new FetchedDocument(url, 0, "", reason);
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String readFixture(String classpathResource) {
        try (InputStream in = StubDocumentReader.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }
}
