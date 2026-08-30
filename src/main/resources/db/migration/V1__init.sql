-- Foundation schema. Flyway owns all DDL; Hibernate runs with ddl-auto=validate.
-- Timestamps are timestamptz throughout so that "when was this observed" stays
-- unambiguous across the timezone of whatever host runs the ingest.

create table feed (
    id              uuid        primary key,
    url             text        not null unique,
    name            text        not null,
    etag            text,
    -- Stored as the raw HTTP header value, not parsed: it is only ever echoed
    -- back in If-Modified-Since, so re-formatting it could only lose fidelity.
    last_modified   text,
    last_polled_at  timestamptz,
    last_status     int,
    active          boolean     not null default true
);

create table document (
    id               uuid        primary key,
    canonical_url    text        not null unique,
    feed_id          uuid        not null references feed (id),
    first_seen_at    timestamptz not null,
    last_checked_at  timestamptz not null,
    -- Null until the first change is detected; drives the re-check policy.
    last_changed_at  timestamptz,
    version_count    int         not null default 0
);

create index idx_document_feed_id on document (feed_id);

-- Immutable, append-only: one row per observed revision of a document.
create table document_version (
    id                 uuid        primary key,
    document_id        uuid        not null references document (id),
    fetched_at         timestamptz not null,
    published_at       timestamptz,
    -- False when the publication date had to be inferred or truncated, so that
    -- later analysis can tell a real timestamp from a best guess.
    published_at_exact boolean     not null default true,
    feed_title         text,
    page_title         text,
    -- Ordered list of strings.
    paragraphs         jsonb       not null,
    content_hash       char(64)    not null,
    simhash            bigint,
    -- Path or key into external storage, never the HTML itself.
    raw_html_ref       text,
    http_status        int         not null,
    -- Guarantees that re-observing identical content cannot create a version.
    constraint uq_document_version_document_content unique (document_id, content_hash)
);

create index idx_document_version_document_id on document_version (document_id);
create index idx_document_version_content_hash on document_version (content_hash);
create index idx_document_version_simhash on document_version (simhash);

create table change (
    id              uuid        primary key,
    document_id     uuid        not null references document (id),
    from_version_id uuid        not null references document_version (id),
    to_version_id   uuid        not null references document_version (id),
    detected_at     timestamptz not null,
    -- COSMETIC | CORRECTION | SUBSTANTIVE
    classification  text        not null,
    title_changed   boolean     not null default false,
    rationale       text        not null,
    diff_payload    jsonb       not null,
    -- One change per ordered pair of versions; re-running the analysis over the
    -- same pair must not accumulate duplicates.
    constraint uq_change_from_to unique (from_version_id, to_version_id)
);

create index idx_change_document_id on change (document_id);
create index idx_change_detected_at on change (detected_at);
create index idx_change_classification on change (classification);
