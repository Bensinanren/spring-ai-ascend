package com.huawei.ascend.examples.deepresearch.read;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stub-routing test: the {@link StubReadUrlTool} maps URL hosts to the
 * /fixtures/*.html set (TOPOLOGY §4.2) and runs the same extraction pipeline
 * as prod, so every doc_type branch is reachable offline.
 */
class StubReadUrlToolTest {

    @Test
    void routes_volcengine_to_pricing_page() {
        Object out = StubReadUrlTool.read(Map.of(
                "url", "https://www.volcengine.com/product/ark",
                "focus_question", "doubao 价格"));
        Map<?, ?> r = (Map<?, ?>) out;
        Map<?, ?> meta = (Map<?, ?>) r.get("metadata");
        assertThat(meta.get("doc_type")).isEqualTo("pricing_page");
        assertThat(r.get("title")).asString().contains("定价");
        assertThat((List<?>) r.get("sections")).isNotEmpty();
        assertThat(meta.get("author")).asString().contains("火山引擎");
    }

    @Test
    void routes_bailian_to_pricing_page() {
        Object out = StubReadUrlTool.read(Map.of("url", "https://bailian.aliyun.com/price"));
        Map<?, ?> r = (Map<?, ?>) out;
        assertThat(((Map<?, ?>) r.get("metadata")).get("doc_type")).isEqualTo("pricing_page");
    }

    @Test
    void routes_blog_host_to_blog() {
        Object out = StubReadUrlTool.read(Map.of("url", "https://blog.csdn.net/article/123"));
        Map<?, ?> r = (Map<?, ?>) out;
        Map<?, ?> meta = (Map<?, ?>) r.get("metadata");
        assertThat(meta.get("doc_type")).isEqualTo("blog");
        assertThat(r.get("content_markdown")).asString().isNotEmpty();
    }

    @Test
    void routes_spa_host_to_spa_blocked() {
        Object out = StubReadUrlTool.read(Map.of("url", "https://spa.example.com/console"));
        Map<?, ?> r = (Map<?, ?>) out;
        assertThat(((Map<?, ?>) r.get("metadata")).get("doc_type")).isEqualTo("spa_blocked");
    }

    @Test
    void routes_cloudflare_host_to_cloudflare_403() {
        Object out = StubReadUrlTool.read(Map.of("url", "https://cloudflare.example.com/protected"));
        Map<?, ?> r = (Map<?, ?>) out;
        Map<?, ?> meta = (Map<?, ?>) r.get("metadata");
        assertThat(meta.get("doc_type")).isEqualTo("cloudflare_403");
    }

    @Test
    void unmatched_host_returns_other() {
        Object out = StubReadUrlTool.read(Map.of("url", "https://totally-unknown-host.example.com/x"));
        Map<?, ?> r = (Map<?, ?>) out;
        assertThat(((Map<?, ?>) r.get("metadata")).get("doc_type")).isEqualTo("other");
    }
}
