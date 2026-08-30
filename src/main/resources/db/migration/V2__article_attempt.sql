-- Remembers that a link was attempted, not only that a document was created.
-- Additive: nothing in V1 is renamed or removed.
--
-- Keyed by the identity the ingest budget ranks by -- the canonicalised feed link,
-- before any redirect or rel=canonical is known -- and not by document_id, because
-- the links this table exists for are exactly the ones that never yield a
-- document: robots.txt refusals, paywall stubs, binaries, unusable URLs.
create table article_attempt (
    link_identity   text        primary key,
    last_attempt_at timestamptz not null,
    -- Consecutive failures. Reset to zero by a successful attempt, so this is a
    -- strike count and not a lifetime total.
    failure_count   int         not null default 0
);

-- The budget ranks least recently attempted first over the whole table.
create index idx_article_attempt_last_attempt_at on article_attempt (last_attempt_at);
