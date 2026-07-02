package com.huawei.ascend.examples.deepresearch.read;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4.3 unit test for the prod read_url path. Spins up a local HTTP server
 * (WireMock standalone) that returns inline HTML fixtures, injects a
 * proxy-disabled {@link HttpClient} into {@link ReadUrlTool}, and asserts the
 * readability4j extraction + doc_type classification for the pricing / SPA /
 * Cloudflare-403 cases.
 */
class ReadUrlToolTest {

    private static WireMockServer wm;
    private static HttpClient http;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(wireMockConfig().dynamicPort());
        wm.start();
        // Proxy disabled so a system -Dhttps.proxyHost can't divert localhost
        // WireMock traffic to a corporate proxy.
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of(null))
                .build();
    }

    @AfterAll
    static void stop() {
        if (wm != null) {
            wm.stop();
        }
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
        ReadUrlTool.useDocumentReader(new HtmlDocumentReader(http));
    }

    @Test
    void extracts_pricing_page() {
        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head>"
                + "<meta charset=\"UTF-8\"><title>Example 平台 - 模型定价</title>"
                + "<meta name=\"author\" content=\"测试作者\">"
                + "<meta property=\"article:published_time\" content=\"2025-01-01T00:00:00+08:00\">"
                + "</head><body>"
                + "<h1>Example 平台 模型定价</h1>"
                + "<p>本页说明 Example 平台各模型的计费方式与价格，按 token 计费，"
                + "输入与输出分别计价，所有价格以人民币元每千 token 为单位。</p>"
                + "<h2>计费方式</h2>"
                + "<p>按量付费，无需预购套餐即可调用各模型，token 用量由平台统一计量，"
                + "调用失败不产生费用。</p>"
                + "<h2>价格表</h2>"
                + "<p>输入 0.0008 元/千 token，输出 0.002 元/千 token，多模态模型另行计费。</p>"
                + "</body></html>";
        wm.stubFor(get(urlEqualTo("/pricing"))
                .willReturn(okForContentType("text/html; charset=UTF-8", html)));

        Object out = ReadUrlTool.read(Map.of(
                "url", wm.url("/pricing"),
                "focus_question", "模型价格是多少"));

        assertThat(out).isInstanceOf(Map.class);
        Map<?, ?> r = (Map<?, ?>) out;
        assertThat(r.get("title")).asString().contains("定价");
        assertThat((List<?>) r.get("sections")).isNotEmpty();
        assertThat(r.get("content_markdown")).asString().isNotEmpty();

        Map<?, ?> meta = (Map<?, ?>) r.get("metadata");
        assertThat(meta.get("doc_type")).isEqualTo("pricing_page");
        assertThat(meta.get("author")).isEqualTo("测试作者");
        assertThat(meta.get("publish_date")).asString().startsWith("2025-01-01");
    }

    @Test
    void detects_cloudflare_403() {
        wm.stubFor(get(urlEqualTo("/blocked"))
                .willReturn(status(403).withBody("Just a moment...")));

        Object out = ReadUrlTool.read(Map.of("url", wm.url("/blocked")));

        Map<?, ?> r = (Map<?, ?>) out;
        Map<?, ?> meta = (Map<?, ?>) r.get("metadata");
        assertThat(meta.get("doc_type")).isEqualTo("cloudflare_403");
        assertThat(r.get("summary")).asString().contains("403");
    }

    @Test
    void detects_spa_blocked() {
        String html = "<!DOCTYPE html><html><head><title>SPA App</title></head><body>"
                + "<div id=\"app\"></div>"
                + "<script>var x=1; document.getElementById('app').innerHTML='rendered';</script>"
                + "</body></html>";
        wm.stubFor(get(urlEqualTo("/spa"))
                .willReturn(okForContentType("text/html; charset=UTF-8", html)));

        Object out = ReadUrlTool.read(Map.of("url", wm.url("/spa")));

        Map<?, ?> r = (Map<?, ?>) out;
        Map<?, ?> meta = (Map<?, ?>) r.get("metadata");
        assertThat(meta.get("doc_type")).isEqualTo("spa_blocked");
        assertThat(r.get("summary")).asString().contains("SPA");
    }

    @Test
    void missing_url_returns_other() {
        Object out = ReadUrlTool.read(Map.of("focus_question", "irrelevant"));
        Map<?, ?> r = (Map<?, ?>) out;
        Map<?, ?> meta = (Map<?, ?>) r.get("metadata");
        assertThat(meta.get("doc_type")).isEqualTo("other");
    }
}
