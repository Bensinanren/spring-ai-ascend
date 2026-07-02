package com.huawei.ascend.examples.deepresearch.read;

import java.util.Locale;

/**
 * Document classification emitted in {@code metadata.doc_type} — see TOPOLOGY
 * §3.3. The root DeepAgent branches its rescheduling strategy on these values
 * (e.g. {@code spa_blocked} -> change source, {@code cloudflare_403} -> down
 * -weight the host).
 *
 * <p>The wire form is the lowercased enum name with underscores preserved
 * ({@code PRICING_PAGE -> "pricing_page"}, {@code SPA_BLOCKED ->
 * "spa_blocked"}, {@code CLOUDFLARE_403 -> "cloudflare_403"}), matching the
 * contract string set in TOPOLOGY §3.3.
 */
public enum DocType {
    PRICING_PAGE,
    BLOG,
    NEWS,
    DOC,
    SPA_BLOCKED,
    CLOUDFLARE_403,
    OTHER;

    /** Stable wire string for the A2A contract output. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
