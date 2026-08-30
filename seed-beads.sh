#!/usr/bin/env bash
set -euo pipefail

mk() { # $1=title $2=type $3=priority, description from stdin
  local d; d=$(cat)
  bd create "$1" -t "$2" -p "$3" --description="$d" --json | jq -r '.id'
}

# ---------------------------------------------------------------- Epics
E1=$(mk "Ingest" epic 1 <<'EOF'
Fetch feeds, parse them, retrieve and clean article full text.
Everything that brings outside data into the system.
EOF
)
E2=$(mk "Identity & Versioning" epic 1 <<'EOF'
Stable document identity, immutable revisions, diffing and change
classification. The domain core of the project.
EOF
)
E3=$(mk "Analysis & Output" epic 2 <<'EOF'
Persistent change records, cross-source clustering, REST API and a
thin web interface.
EOF
)

# ------------------------------------------------------- T0: Bootstrap
T0=$(mk "Bootstrap: project skeleton, Docker, datasource" task 0 <<'EOF'
Goal: a runnable, empty Spring Boot application with a working database
connection. No domain code.

Acceptance:
- Maven project generated with: web, data-jpa, flyway, validation,
  testcontainers. Java 25.
- docker-compose.yml running PostgreSQL 18.6 on a non-default port, with a
  named volume so data survives restarts.
- application.yml with the datasource; credentials via environment
  variables with local defaults.
- Separate test profile; Testcontainers spins up its own Postgres and does
  not reuse the compose container.
- A context-load test passes: the application starts and Flyway runs
  against an empty schema.
- README section: how to start the database and run the app locally.
- Record the resolved Spring Boot, Maven and Flyway versions in the close
  reason.

Boundary: no entities, no repositories, no migrations beyond an empty
baseline, no business code.
Flyway: none (the first migration comes with the foundation ticket).
EOF
)

# ------------------------------------------------------ T1: Foundation
T1=$(mk "Foundation: entities, repositories, Flyway V1" task 0 <<'EOF'
Goal: establish the shared data model every other ticket builds on.
No business logic.

Schema (names and types are binding):

Feed
  id              UUID, PK
  url             text, not null, unique
  name            text, not null
  etag            text, nullable
  lastModified    text, nullable            -- raw HTTP header value
  lastPolledAt    timestamptz, nullable
  lastStatus      int, nullable
  active          boolean, not null, default true

Document
  id              UUID, PK
  canonicalUrl    text, not null, unique
  feedId          UUID, FK -> Feed, not null
  firstSeenAt     timestamptz, not null
  lastCheckedAt   timestamptz, not null
  lastChangedAt   timestamptz, nullable     -- drives the re-check policy
  versionCount    int, not null, default 0

DocumentVersion                             -- immutable, append-only
  id               UUID, PK
  documentId       UUID, FK -> Document, not null
  fetchedAt        timestamptz, not null
  publishedAt      timestamptz, nullable
  publishedAtExact boolean, not null, default true
  feedTitle        text, nullable
  pageTitle        text, nullable
  paragraphs       jsonb, not null          -- ordered list of strings
  contentHash      char(64), not null, indexed
  simhash          bigint, nullable, indexed
  rawHtmlRef       text, nullable           -- path or key, never the HTML
  httpStatus       int, not null
  unique (documentId, contentHash)

Change
  id              UUID, PK
  documentId      UUID, FK -> Document, not null, indexed
  fromVersionId   UUID, FK -> DocumentVersion, not null
  toVersionId     UUID, FK -> DocumentVersion, not null
  detectedAt      timestamptz, not null, indexed
  classification  text, not null, indexed   -- COSMETIC|CORRECTION|SUBSTANTIVE
  titleChanged    boolean, not null, default false
  rationale       text, not null
  diffPayload     jsonb, not null
  unique (fromVersionId, toVersionId)

Acceptance:
- One JPA entity per table, one Spring Data repository each, default
  methods only.
- Flyway V1__init.sql matching the entities exactly.
- A smoke test persists one instance of each and reads it back.

Boundary: no services, no controllers, no ingest logic. Do not add fields
beyond this list -- later tickets extend the schema additively.
Flyway: V1.
EOF
)

# --------------------------------------------------------- Epic 1: Ingest
T2=$(mk "Feed fetcher" task 1 <<'EOF'
Goal: fetch the feeds listed in feeds.yml and store the raw response body
together with retrieval time and HTTP status.

Acceptance:
- ETag and Last-Modified are stored per feed and sent as If-None-Match /
  If-Modified-Since on the next request. A 304 creates no new record.
- At most one concurrent request per host, with a configurable minimum
  interval between requests.
- 5xx and timeouts: three attempts with exponential backoff, then logged
  as failed. A broken feed never aborts the run.
- WireMock tests for 200, 304, 500, timeout and redirect.

Boundary: no content parsing (T3), no full text (T6).
Flyway: none.
EOF
)

T3=$(mk "Feed parser (RSS 2.0 + Atom)" task 1 <<'EOF'
Goal: turn a raw feed body into a uniform list of Entry objects
(title, link, summary, published, updated, guid).

Acceptance:
- RSS 2.0 and Atom are both supported; callers see no difference.
- Missing mandatory fields cause the entry to be skipped with a log
  record, never the whole feed to fail.
- Fixtures under test/resources/feeds/: one real example per format plus
  one deliberately malformed file.

Library: rome.
Boundary: no date (T5) or URL (T9) normalisation.
Flyway: none.
EOF
)

T4=$(mk "Encoding resolution" task 2 <<'EOF'
Goal: determine the actual encoding of a feed or HTML page when the
available declarations contradict each other.

Acceptance:
- Considers BOM, HTTP Content-Type charset, and XML declaration or HTML
  meta charset, in a documented order of precedence.
- Contradictions between sources are logged, not silently resolved.
- Unknown or invalid charsets fall back to UTF-8.
- Fixtures covering at least three real conflict cases, including a
  latin-1 document declaring itself as UTF-8.

Boundary: no language or content detection.
Flyway: none.
EOF
)

T5=$(mk "Date normalisation" task 2 <<'EOF'
Goal: derive a reliable publication timestamp in UTC from an entry's
date fields.

Acceptance:
- RFC 822 and ISO 8601 in their common variants are parsed.
- Missing timezone: documented assumption, result flagged via
  publishedAtExact = false.
- A date more than one hour in the future is discarded in favour of the
  retrieval time, and the event is logged.
- published and updated are kept separate, never conflated.
- Parameterised test with at least twelve real-world formats.

Boundary: no change detection based on updated (that is T14).
Flyway: none.
EOF
)

T6=$(mk "Article fetcher" task 1 <<'EOF'
Goal: follow an entry's link and retrieve the complete article HTML.

Acceptance:
- Redirect chains are followed up to a limit; the final URL is recorded.
- Rate limiting and backoff as in T2, sharing the same configuration.
- The host's robots.txt is respected.
- Raw HTML is stored outside the database; only rawHtmlRef is persisted.
- Non-HTML responses (PDF, images) are detected and skipped.

Boundary: no text extraction (T7).
Flyway: none.
EOF
)

T7=$(mk "Boilerplate removal" task 1 <<'EOF'
Goal: extract the actual article prose from the HTML -- without
navigation, cookie banners, ad blocks, comments, footers or
related-article boxes.

Acceptance:
- DETERMINISM: calling it twice with identical input yields byte-identical
  output. This is the single most important property, because T10 hashes
  the result. An explicit test must verify it.
- The result is an ordered list of paragraphs, not one string -- T12
  diffs on it.
- The page title is extracted separately and returned alongside.
- Fixtures under test/resources/boilerplate/: one HTML file per source
  plus its expected extraction.
- Known weaknesses belong in the close reason. Do not gloss over them.

Library: jsoup as the parser.
Boundary: NO ready-made readability port (crux, boilerpipe or similar) --
the extraction logic is the substance of this ticket. No sanitisation for
display, no image extraction.
Flyway: none.
EOF
)

T8=$(mk "Ingest orchestration" task 1 <<'EOF'
Goal: wire T2/T3/T6/T7 into a single run that is testable and can be
triggered by hand.

Acceptance:
- IngestService.runOnce() is synchronous, holds the entire flow, and
  returns a result object (checked, new, unchanged, failed).
- The scheduler calls runOnce() and contains no logic of its own.
  Interval configurable, disabled in tests.
- POST /api/ingest/run triggers the same run and returns the result object.
- An integration test performs a full run against WireMock and
  Testcontainers, with no scheduler running.
- A failure on one feed does not end the run.

Boundary: no versioning, no diffing. Only the path from outside to inside.
Flyway: none.
EOF
)

# -------------------------------------- Epic 2: Identity & Versioning
T9=$(mk "URL canonicalisation" task 1 <<'EOF'
Goal: derive a stable identity from the various forms an article URL
can take.

Acceptance:
- Tracking parameters (utm_*, fbclid, gclid and similar) are stripped
  while meaningful parameters are preserved -- the distinction is
  documented and justified.
- rel=canonical from the HTML takes precedence over the fetched URL.
- AMP and mobile variants are folded back to the main form.
- Stability: the same underlying resource always yields the same
  canonicalUrl, across runs.
- Test table with at least twenty real URL pairs.

Boundary: no content comparison -- identity from the URL only.
Flyway: none.
EOF
)

T10=$(mk "Content hash" task 1 <<'EOF'
Goal: compute a hash over the normalised article content that changes
exactly when the content changes.

Acceptance:
- Based on title and paragraph list from T7, not on raw HTML.
- Insensitive to: whitespace variants, ad and session IDs inside the text,
  relative timestamps such as "3 hours ago", typographic variants of
  quotes and dashes.
- Sensitive to: any word change, added or removed paragraphs.
- Test: the same article loaded twice with different ad IDs yields the
  same hash; one changed word yields a different one.

Boundary: no similarity scoring (that is T16).
Flyway: none.
EOF
)

T11=$(mk "Version store" task 0 <<'EOF'
Goal: persist every observed revision of a document, permanently and
immutably.

Acceptance:
- Append-only: existing versions are never overwritten or deleted. Each
  revision is stored in full, not as a delta.
- A new version is created only when contentHash differs from the most
  recently stored revision.
- Maintains Document.versionCount and Document.lastChangedAt in the same
  transaction.
- Queries: all versions of a document in order; a specific version; the
  revision that was current at a given point in time.
- Any two versions must be comparable, not just adjacent ones.
- No retention limit, no expiry.

Boundary: no diffing (T12), no change records (T17).
Flyway: V2.
EOF
)

T12=$(mk "Diff engine" task 1 <<'EOF'
Goal: compute the structured difference between any two versions of a
document.

Acceptance:
- Paragraph-level diff (added, removed, changed, moved) and word-level
  diff within changed paragraphs.
- The result is a data structure, not preformatted text -- T13 and T19
  both consume it. A sealed interface with one record per change kind is
  the expected shape.
- Moved paragraphs are recognised as moves, not as delete plus insert.
- Title changes are reported separately.
- Deterministic, and tested asymmetrically (A->B is not B->A).

Library: java-diff-utils.
Boundary: no judgement about what the change means (T13).
Flyway: none.
EOF
)

T13=$(mk "Change classification" task 1 <<'EOF'
Goal: classify a diff as COSMETIC, CORRECTION or SUBSTANTIVE, with a
traceable rationale.

Acceptance:
- COSMETIC: whitespace, typography, quotation marks, markup only.
- CORRECTION: changes identifiable as corrections -- a correction notice
  in the text, a corrected number, date or name.
- SUBSTANTIVE: everything else, in particular removed paragraphs and
  altered attributions.
- The rationale names the specific locations, not just the class.
- Goldset under test/resources/classification/: at least fifteen diffs
  with expected class, including three borderline cases.
- Cases the scheme does not cover become their own ticket
  (--deps discovered-from). Do not invent a fourth class unilaterally.

Boundary: no cross-document heuristics (T16).
Flyway: none.
EOF
)

T14=$(mk "Re-check policy" task 1 <<'EOF'
Goal: decide when a known article is fetched again. A fixed interval
would be wrong: the interesting edits happen in the first hours after
publication.

Acceptance:
- The interval grows with the age of the article (frequent right after
  publication, not at all after a few days) -- the chosen curve is
  documented and justified.
- An updated field in the feed triggers an immediate re-check.
- An article that has changed repeatedly stays under observation longer
  than one that has been stable.
- The per-host hourly request ceiling is respected.
- Testable as a pure function: state in, decision out -- no HTTP, no clock.

Boundary: no fetcher of its own (uses T6), no scheduler (uses T8).
Flyway: none.
EOF
)

T15=$(mk "Title tracking" task 2 <<'EOF'
Goal: track headline changes separately -- the most visible and most
frequently altered part of an article.

Acceptance:
- History of every title revision per document, independent of whether
  the body changed.
- feedTitle and pageTitle are tracked separately; a divergence between
  them is itself a finding and is recorded.
- Query: all documents with more than N title revisions.

Boundary: no evaluation beyond what T13 provides.
Flyway: none.
EOF
)

# ------------------------------------------ Epic 3: Analysis & Output
T16=$(mk "Cross-source clustering" task 3 <<'EOF'
Goal: recognise the same story across multiple outlets -- typical for
agency copy adopted with light edits by many newsrooms.

Acceptance:
- SimHash or MinHash over the normalised text, threshold configurable
  and justified.
- Clusters span sources, with a similarity score per pair.
- Finding similar documents must not be a full scan over all documents;
  prefiltering is part of the task.
- Test corpus with at least three real cases of the same story in
  different renderings, plus counter-examples.

Boundary: no cluster evaluation, no provenance analysis (who published
first).
Flyway: adds the cluster association additively.
EOF
)

T17=$(mk "Change store" task 1 <<'EOF'
Goal: persist detected changes as first-class records, permanently
queryable -- not merely as a derivable by-product.

Acceptance:
- One Change record per detected change, referencing both versions, with
  timestamp, classification, rationale and diff payload.
- Queryable by document, source, time range and classification, in any
  combination.
- No time limit. A two-year-old change is as retrievable as today's.
- Idempotent: the same version transition never produces two records.
- Appropriate indexes, justified in the close reason.

Boundary: no aggregation, no reports.
Flyway: V3.
EOF
)

T18=$(mk "REST API" task 2 <<'EOF'
Goal: read access to documents, versions and changes.

Acceptance:
- GET /api/documents?source=&since=
- GET /api/documents/{id}/versions
- GET /api/documents/{id}/diff?from=&to=
- GET /api/changes?from=&to=&type=&source=  (paginated)
- Errors return Problem Details (RFC 7807), never stack traces.
- MockMvc tests per endpoint, including empty result sets and invalid
  parameters.

Boundary: no write endpoints other than the ingest trigger from T8.
Flyway: none.
EOF
)

T19=$(mk "Web interface" task 3 <<'EOF'
Goal: a thin interface listing detected changes, with a click-through to
a side-by-side comparison of two revisions.

Acceptance:
- Server-rendered with Thymeleaf. No frontend build, no JS framework,
  no component library.
- List: timestamp, source, title, classification; filter by type and
  time range.
- Diff view: two columns, additions and deletions highlighted, title
  change called out at the top.
- Data comes exclusively through the services from T17/T18. No queries
  of its own.
- Must work without internet access (no CDN references).

Boundary: no login, no editing, no search.
Flyway: none.
EOF
)

# --------------------------------------------------------- Epic linkage
# T0 and T1 stay unparented: they are prerequisites, not feature work.
for t in $T2 $T3 $T4 $T5 $T6 $T7 $T8;       do bd dep add "$t" "$E1" --type parent-child; done
for t in $T9 $T10 $T11 $T12 $T13 $T14 $T15; do bd dep add "$t" "$E2" --type parent-child; done
for t in $T16 $T17 $T18 $T19;               do bd dep add "$t" "$E3" --type parent-child; done

# ------------------------------------------------------------- Blockers
# Syntax: bd dep add <blocked> <blocked-by>
bd dep add "$T1" "$T0"
for t in $T2 $T3 $T6 $T9 $T11; do bd dep add "$t" "$T1"; done
bd dep add "$T4"  "$T3";  bd dep add "$T5"  "$T3";  bd dep add "$T6"  "$T3"
bd dep add "$T7"  "$T6";  bd dep add "$T8"  "$T7";  bd dep add "$T10" "$T7"
bd dep add "$T12" "$T11"; bd dep add "$T13" "$T12"; bd dep add "$T15" "$T12"
bd dep add "$T14" "$T11"; bd dep add "$T16" "$T10"
bd dep add "$T17" "$T13"; bd dep add "$T18" "$T17"; bd dep add "$T19" "$T18"

bd ready
