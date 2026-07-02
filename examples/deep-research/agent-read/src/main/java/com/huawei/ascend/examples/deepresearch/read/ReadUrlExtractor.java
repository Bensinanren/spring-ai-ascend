package com.huawei.ascend.examples.deepresearch.read;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Shared extraction pipeline run by both {@link ReadUrlTool} (prod) and
 * {@link StubReadUrlTool} (stub) — so prod and stub exercise identical logic
 * and cannot drift (TOPOLOGY §4 stub-vs-prod parity).
 *
 * <p>Steps (TOPOLOGY §3.3):
 * <ol>
 *   <li>Map non-2xx status: 403/429 -> {@code cloudflare_403}, 5xx/4xx/empty
 *       -> {@code other} (with error description).</li>
 *   <li>Clean the DOM with jsoup (drop script/style/nav/footer/aside/form/...).</li>
 *   <li>Extract the main article with readability4j (Mozilla Readability port);
 *       fall back to jsoup body text when readability returns null/throws.</li>
 *   <li>SPA detection: extracted text below {@value #SPA_TEXT_THRESHOLD} chars
 *       -> {@code spa_blocked} (never silently emit an empty result).</li>
 *   <li>Classify {@code doc_type} (pricing_page / blog / news / doc / other)
 *       from URL + title + content keywords.</li>
 *   <li>Split into heading-delimited sections and render a markdown body.</li>
 *   <li>Produce an extractive summary fallback (the ReAct LLM refines it when
 *       {@code focus_question} is set).</li>
 * </ol>
 */
final class ReadUrlExtractor {

    /** Below this many chars of extracted text the page is treated as a SPA shell. */
    static final int SPA_TEXT_THRESHOLD = 200;
    private static final int SUMMARY_MAX = 200;
    private static final int MARKDOWN_FALLBACK_CAP = 4000;
    private static final String REMOVE_SELECTOR =
            "script, style, noscript, iframe, nav, footer, header, aside, form, button, svg, canvas";

    private ReadUrlExtractor() {
    }

    static ReadContent extract(FetchedDocument doc, String focusQuestion) {
        String url = doc.url() == null ? "" : doc.url();

        if (doc.hasError() || doc.statusCode() == 0) {
            return errorContent(url, DocType.OTHER,
                    "fetch error: " + (doc.hasError() ? doc.errorMessage() : "no response"));
        }
        int status = doc.statusCode();
        if (status == 403 || status == 429) {
            return errorContent(url, DocType.CLOUDFLARE_403, "http " + status + " blocked (cloudflare / rate-limited)");
        }
        if (status >= 500) {
            return errorContent(url, DocType.OTHER, "http " + status + " server error");
        }
        if (status >= 400) {
            return errorContent(url, DocType.OTHER, "http " + status + " client error");
        }

        String html = doc.html() == null ? "" : doc.html();
        if (html.isBlank()) {
            return errorContent(url, DocType.OTHER, "empty response body");
        }

        Document jsoupDoc = Jsoup.parse(html, url);
        jsoupDoc.select(REMOVE_SELECTOR).remove();

        String title = jsoupDoc.title();
        if (title == null || title.isBlank()) {
            Element h1 = jsoupDoc.selectFirst("h1");
            title = h1 != null ? h1.text() : "";
        }
        String author = extractAuthor(jsoupDoc);
        String publishDate = extractPublishDate(jsoupDoc);

        String cleanedHtml = jsoupDoc.outerHtml();
        String contentHtml = "";
        String textContent = "";
        String excerpt = "";
        String byline = author;

        Article article = null;
        try {
            article = new Readability4J(url, cleanedHtml).parse();
        } catch (RuntimeException ex) {
            article = null;
        }
        if (article != null) {
            String t = article.getTitle();
            if (t != null && !t.isBlank()) {
                title = t;
            }
            contentHtml = nullToEmpty(article.getContent());
            textContent = nullToEmpty(article.getTextContent());
            excerpt = nullToEmpty(article.getExcerpt());
            String bl = article.getByline();
            if (bl != null && !bl.isBlank()) {
                byline = bl;
            }
        } else {
            Element body = jsoupDoc.body();
            contentHtml = body != null ? body.html() : "";
            textContent = body != null ? body.text() : "";
        }

        int textLen = textContent.length();
        if (textContent.isBlank() || textLen < SPA_TEXT_THRESHOLD) {
            return new ReadContent(
                    title,
                    textContent.isBlank() ? "" : cap(textContent, 500),
                    List.of(),
                    "正文过短（" + textLen + " 字），疑似 SPA 客户端渲染页面，jsoup 抓不到正文，建议换源（静态文档/新闻/博客镜像）",
                    new ReadMetadata(nullIfBlank(byline), nullIfBlank(publishDate), DocType.SPA_BLOCKED));
        }

        DocType docType = classify(jsoupDoc, url, textContent);
        List<ReadSection> sections = splitSections(contentHtml.isBlank() ? cleanedHtml : contentHtml);
        String contentMarkdown = toMarkdown(title, sections, textContent);
        String summary = buildSummary(excerpt, textContent);

        return new ReadContent(
                title,
                contentMarkdown,
                sections,
                summary,
                new ReadMetadata(nullIfBlank(byline), nullIfBlank(publishDate), docType));
    }

    private static ReadContent errorContent(String url, DocType docType, String message) {
        return new ReadContent("", "", List.of(), message, new ReadMetadata(null, null, docType));
    }

    private static DocType classify(Document doc, String url, String textContent) {
        // Strong URL/title signals first: a blog/news/docs URL or title is
        // authoritative even when the body happens to discuss pricing (e.g. a
        // blog post that compares model prices). Pricing is a content signal
        // checked last, since pricing pages may not carry "pricing" in the URL.
        String lowerTitle = (doc.title() + " " + url).toLowerCase(Locale.ROOT);
        if (containsAny(lowerTitle, "博客", "blog", "转载")) {
            return DocType.BLOG;
        }
        if (containsAny(lowerTitle, "新闻", "news", "报道", "press release")) {
            return DocType.NEWS;
        }
        if (containsAny(lowerTitle, "文档", "docs", "documentation", "api reference", "开发指南")) {
            return DocType.DOC;
        }
        String lowerAll = (url + " " + doc.title() + " " + textContent).toLowerCase(Locale.ROOT);
        if (containsAny(lowerAll, "定价", "pricing", "价格", "price", "计费", "billing", "收费")) {
            return DocType.PRICING_PAGE;
        }
        return DocType.OTHER;
    }

    private static List<ReadSection> splitSections(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Document doc = Jsoup.parse(html);
        Element body = doc.body();
        if (body == null) {
            return List.of();
        }
        // readability4j getContent() frequently wraps the article in a single
        // <div>/<article>; unwrap one level so heading siblings become
        // top-level children and the h1-h3 split below actually fires.
        Element root = body;
        if (body.childrenSize() == 1) {
            Element only = body.child(0);
            String tag = only.tagName().toLowerCase(Locale.ROOT);
            if (tag.equals("div") || tag.equals("article") || tag.equals("section") || tag.equals("main")) {
                root = only;
            }
        }
        List<ReadSection> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = "";
        for (Element el : root.children()) {
            String tag = el.tagName().toLowerCase(Locale.ROOT);
            if (tag.equals("h1") || tag.equals("h2") || tag.equals("h3")) {
                if (current.length() > 0 || !currentHeading.isEmpty()) {
                    sections.add(new ReadSection(currentHeading, current.toString().trim()));
                }
                currentHeading = el.text().trim();
                current.setLength(0);
            } else {
                String t = el.text().trim();
                if (!t.isEmpty()) {
                    if (current.length() > 0) {
                        current.append("\n\n");
                    }
                    current.append(t);
                }
            }
        }
        if (current.length() > 0 || !currentHeading.isEmpty()) {
            sections.add(new ReadSection(currentHeading, current.toString().trim()));
        }
        return sections;
    }

    private static String toMarkdown(String title, List<ReadSection> sections, String textContent) {
        StringBuilder md = new StringBuilder();
        if (title != null && !title.isBlank()) {
            md.append("# ").append(title.trim()).append("\n\n");
        }
        if (sections.isEmpty()) {
            md.append(cap(textContent == null ? "" : textContent, MARKDOWN_FALLBACK_CAP));
            return md.toString();
        }
        for (ReadSection s : sections) {
            if (!s.heading().isBlank()) {
                md.append("## ").append(s.heading()).append("\n\n");
            }
            if (!s.body().isBlank()) {
                md.append(s.body()).append("\n\n");
            }
        }
        return md.toString().trim();
    }

    private static String buildSummary(String excerpt, String textContent) {
        String source = (excerpt != null && !excerpt.isBlank()) ? excerpt : textContent;
        if (source == null || source.isBlank()) {
            return "";
        }
        return source.length() > SUMMARY_MAX ? source.substring(0, SUMMARY_MAX) : source;
    }

    private static String extractAuthor(Document doc) {
        String a = metaContent(doc, "name", "author");
        if (a == null) {
            a = metaContent(doc, "property", "article:author");
        }
        if (a == null) {
            Element el = doc.selectFirst("a[rel=author]");
            if (el != null) {
                a = el.text();
            }
        }
        return nullIfBlank(a);
    }

    private static String extractPublishDate(Document doc) {
        String d = metaContent(doc, "property", "article:published_time");
        if (d == null) {
            d = metaContent(doc, "name", "date");
        }
        if (d == null) {
            d = metaContent(doc, "name", "publishdate");
        }
        if (d == null) {
            Element el = doc.selectFirst("time[datetime]");
            if (el != null) {
                d = el.attr("datetime");
            }
        }
        return nullIfBlank(d);
    }

    private static String metaContent(Document doc, String attr, String key) {
        Element el = doc.selectFirst("meta[" + attr + "=\"" + key + "\"]");
        if (el != null) {
            String c = el.attr("content");
            if (!c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (n != null && !n.isEmpty() && haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
