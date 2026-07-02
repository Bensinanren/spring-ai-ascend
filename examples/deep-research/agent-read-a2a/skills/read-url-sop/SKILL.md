# Read URL SOP

Skill for the deep-research read sub-agent: how to issue `read_url` calls that return a clean, contract-shaped extraction in a single call.

## When to apply

The root DeepAgent forwards a URL (and usually a `focus_question`) to read. Treat it as a single read task — no clarification, no follow-up dispatch.

## Step 1 — Validate the input

- `url` must be present and absolute http(s). If absent, return the contract with `doc_type=other` and an error note in `summary`; do not fabricate a URL.
- `focus_question` may be null. When non-null, the `summary` you emit must answer it using only `content_markdown`.

## Step 2 — Call read_url exactly once

Call `read_url({ url, focus_question })` once. Do not call it twice for the same URL. The tool does HTTP fetch + jsoup cleaning + readability4j extraction + SPA/Cloudflare detection internally; a second call returns the same payload.

## Step 3 — Branch on metadata.doc_type

- `pricing_page` / `blog` / `news` / `doc` — normal extraction. Pass `title`, `content_markdown`, `sections` through unmodified.
- `spa_blocked` — the page is a client-rendered SPA; jsoup saw only an empty shell. Emit the full contract shape (empty `content_markdown`/`sections` are fine) so the root agent sees the signal and changes source. Do NOT apologise in free text.
- `cloudflare_403` — HTTP 403/429 (Cloudflare / rate-limit). Same as above: emit the contract with the `cloudflare_403` signal so the root down-weights the host.
- `other` — fetch error / non-2xx / empty body. Emit the contract with the error note in `summary`.

## Step 4 — Refine summary (only when focus_question is set)

The tool returns an extractive `summary` fallback (readability4j excerpt or first 200 chars). When `focus_question` is non-null, rewrite `summary` (≤200 字, Chinese) to answer that question using only `content_markdown`. When `focus_question` is null, keep the tool's summary or trim it to ≤200 字.

## Step 5 — Pass-through rule

Never rewrite `content_markdown`, never invent `sections`, never alter `metadata.doc_type`. The root agent owns verification and synthesis; your job is a faithful extraction + a focused summary.

## Output contract reminder

Return ONLY:
```
{ "title", "content_markdown", "sections": [{"heading","body"}], "summary", "metadata": {"author","publish_date","doc_type"} }
```
