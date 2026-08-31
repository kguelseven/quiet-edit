# quietedit

Detects silent edits to news articles: ingest RSS/Atom feeds, fetch the full
text, version every revision, and diff what changed.

An article that is edited after publication usually says nothing about it. This
service polls the feeds, re-fetches known articles on a decaying schedule, stores
every observed revision append-only, and lets you diff any two of them.

## How it works

One ingest run does the whole path:

1. **Catalogue sync** — `feeds.yaml` is reconciled into the `feed` table (additively).
2. **Feed poll** — conditional GET per feed (`If-None-Match` / `If-Modified-Since`),
   per-host rate limiting, retries on 5xx and transport errors only.
3. **Feed parse** — one jsoup XML pass; RSS 2.0, Atom 1.0 and older dialects come
   out as one uniform entry shape. Dates stay verbatim text until normalised.
4. **Re-check policy** — decides which known articles are due. Pure function:
   state in, one decision per candidate out.
5. **Article fetch** — robots.txt honoured per redirect hop, size-capped, raw HTML
   stored gzipped and content-addressed on disk.
6. **Encoding resolution** — BOM > HTTP charset > document declaration > UTF-8, with
   every losing declaration recorded.
7. **Extraction** — jsoup-only boilerplate removal down to a title plus a flat
   paragraph list. Deterministic, no readability port.
8. **Identity** — canonical URL (tracking-parameter denylist, AMP unwrapping,
   `rel=canonical` with guards).
9. **Versioning** — SHA-256 over folded text; a revision is appended only when the
   hash differs from the newest one. Append-only is enforced by database triggers.

### Domain terms

| Term | Meaning |
|---|---|
| **Feed** | a subscribed RSS or Atom source |
| **Document** | one article, identified by its canonical URL |
| **DocumentVersion** | one observed revision, immutable and append-only |
| **Change** | a detected difference between two versions (**not yet populated**) |

## Requirements

- Java 25
- Maven 3.9.12
- Docker (local database and Testcontainers-backed tests)

## Running locally

```bash
docker compose up -d      # PostgreSQL 18.6 on localhost:55432
mvn spring-boot:run       # http://localhost:8080, Flyway migrates on startup
```

Data lives in the named volume `quietedit-pgdata` and survives `docker compose down`.
`docker compose down -v` discards it.

The scheduler is on by default and calls the same `runOnce()` the REST endpoint
does, with a fixed delay of 5 minutes measured from the end of the previous run.

## REST API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/ingest/run` | Run the full ingest once. `409` if a run is already in flight. |
| `POST` | `/api/ingest/feeds/run` | Poll the feeds only, no article fetching. |
| `GET`  | `/api/documents/changed?limit=50` | Documents with more than one revision, most recently changed first. `limit` clamped to 200. |
| `GET`  | `/api/documents/{id}/revisions` | The document's full history, oldest first. No text. |
| `GET`  | `/api/documents/{id}/diff?from=&to=` | Diff of two revisions. Defaults to the newest pair; `to` alone means "against its predecessor". |

Diffs are computed per request, never stored: a diff is a pure function of two
immutable rows, so recomputing can never disagree with the store.

Errors are RFC 7807 Problem Details. Stack traces never leave the process.

## Configuration

`feeds.yaml` at the repository root lists the sources:

```yaml
feeds:
  - url: "https://www.srf.ch/news/bnf/rss/1646"
    name: "SRF News"
```

Environment variables (all have local defaults):

| Variable | Default |
|---|---|
| `QUIETEDIT_DB_URL` | `jdbc:postgresql://localhost:55432/quietedit` |
| `QUIETEDIT_DB_USER` / `QUIETEDIT_DB_PASSWORD` | `quietedit` |
| `QUIETEDIT_PORT` | `8080` |
| `QUIETEDIT_FEEDS_FILE` | `file:./feeds.yaml` |
| `QUIETEDIT_SCHEDULE_ENABLED` | `true` |
| `QUIETEDIT_SCHEDULE_INTERVAL` | `5m` |
| `QUIETEDIT_MAX_ARTICLES_PER_RUN` | `100` |
| `QUIETEDIT_MAX_HOST_REQUESTS_PER_HOUR` | `120` |
| `QUIETEDIT_RAW_HTML_DIR` | `./data/raw-html` |

The re-check curve, the observation window and the fetch timeouts live under
`quietedit.ingest.*` in `application.yml`; the reasoning behind each number is in
the Javadoc of the class that reads it (`RecheckPolicy` above all).

### The re-check curve

An article is re-checked after `age-factor` (0.25) of its age, floored at 10
minutes and capped at 12 hours. Self-similar rather than tiered, because the rate
at which an article is still being edited falls off roughly like 1/age. An observed
edit restarts the curve at its steep end. A candidate is retired once nothing has
happened to it for `observation-window` × (1 + observed changes), capped at 4×.

A feed's `updated` field makes a candidate due immediately — but a feed that
stamps `updated` on every render loses that power after 20 consecutive claims
that produced no revision, and earns it back with the first that does.

## Tests

```bash
mvn verify
```

Tests run under the `test` profile and start their own throwaway PostgreSQL
container via Testcontainers. They never touch the compose database, so only a
running Docker daemon is required. Outbound HTTP is stubbed with WireMock.

Test policy: core logic only — extraction, hashing, canonicalisation, diffing,
date normalisation, re-check policy. Plus integration tests for the full ingest
run and the append-only guarantee. No tests for wiring, mappers or getters.

## Known limitations

### Not built yet

- **Change classification does not run.** The `change` table and the `Classification`
  enum exist, but nothing writes to them. Two classification rules are implemented
  and tested — `EncodingRepair` (mojibake fixed between revisions) and
  `IndexLineRewrite` (a ticker restructuring its own index line) — but no classifier
  calls them yet. Today the system reports *that* something changed and *what* the
  text difference is, never *what kind* of change it was.
- **No title tracking**, **no change store**, **no change REST API**, **no web
  interface**, **no cross-source clustering.**
- **No authentication** on any endpoint.

### Operational

- **Single process only.** The "one run at a time" guard is an in-process flag. A
  second instance would need a database lock.
- **The per-host hourly ceiling is approximate.** Spend is reconstructed from the
  candidates' `lastCheckedAt`, which undercounts by a bounded amount. There is no
  exact request ledger.
- **Nothing is ever deleted.** The raw HTML store has no retention rule, and
  neither does the attempt log.
- **An article that fails three times is abandoned permanently**, with no path back.
- **Article age is measured from discovery, not publication.** An archive article
  entering a feed today gets a fresh article's attention.
- **A feed removed from `feeds.yaml` is not deactivated** — the catalogue sync is
  additive on purpose, because documents reference the feed row.
- **A distrusted feed whose articles are all retired can never be tested again**,
  so it cannot earn its `updated` claims back.
- The `changed` listing is capped at 200 rows with no total and no offset.

### Extraction

- The furniture vocabulary is a curated DE/EN list and will miss differently-named
  boilerplate. There is no per-host comparison across many articles, which is what
  would tell a page's own furniture from its prose.
- `<figure>` is removed wholesale, so figure-marked pull quotes are lost.
- A rail caption a publisher styles exactly like its body subheadings survives.
- Ticker entry bodies wrapped in `div` rather than `p` are not extracted at all.
- Promo boxes that read as prose (an app-push box, for instance) can survive as
  paragraphs.
- Text in no prose block is dropped; JS-injected text is invisible by design.

### Detection quality

- **An edit confined to a masked span is invisible.** The hash deliberately folds
  ad and session identifiers, relative timestamps and typographic variants, so a
  change inside one of them produces no revision. The alternative was reporting a
  change on every single fetch.
- **The diff engine cannot tell a heading from a paragraph** — extraction yields a
  flat list — so one similarity threshold (0.5, relaxed to 1/3 for a replacement in
  a balanced run) has to serve both. The relaxed value is calibrated against
  observed data with a thin margin: 0.36 for a real correction against 0.25 for the
  closest observed non-pair.
- Paragraph split/merge is not recognised as such; repeated identical paragraphs are
  interchangeable to the move pass; pairing is greedy in reading order, not globally
  optimal.
- The `IndexLineRewrite` rule can claim a prose paragraph that imitates the shape of
  a ticker index line, and is all-or-nothing per paragraph.
- An encoding repair shipped together with a real edit hides that edit; a
  clean-charset-to-clean-charset switch is deliberately not claimed as a repair.

### Input handling

- **URL canonicalisation:** `index.html` and the bare directory are not folded;
  path-based mobile variants beyond a leading `/m/` are left alone; hashbang routes
  lose their identity with the fragment; a cross-publisher `rel=canonical` is
  trusted, which folds syndicated copies together; the tracking-parameter denylist
  will lag new campaign parameters.
- **Encoding:** a BOM-less UTF-16 body with no header charset is undiagnosable
  without content detection, which is out of scope. Bytes invalid in their declared
  charset are decoded with replacement characters — deterministically, so the hash
  stays stable, but the text is wrong.
- **Dates:** a missing timezone becomes UTC and is flagged inexact; a two-digit year
  windows into 1970–2069; a date more than an hour ahead of the fetch is replaced by
  the fetch time.
- **Feed parsing** detects a truncated body from jsoup's source-range behaviour
  rather than a documented guarantee. Bodies a strict XML parser would reject (a
  stray `&`, say) are repaired and parsed.
- `robots.txt` paths are compared as written: a rule that percent-encodes a
  character the URL spells literally will not match.

## Layout

```
src/main/java/org/korhan/quietedit/
  ingest/      feeds, fetching, extraction, re-check policy, orchestration
  versioning/  canonical URLs, hashing, version store, diff engine, diff API
  analysis/    change entity, classification rules (not yet wired)
```

Layering is Controller → Service → Repository; business logic lives in the service
layer only. Scheduled jobs contain no logic — they call a synchronous `runOnce()`
that is also reachable over REST and testable without a scheduler.
