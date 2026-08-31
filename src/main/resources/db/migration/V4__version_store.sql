-- Makes document_version append-only in the database rather than by convention, and
-- gives every revision a stable ordinal. Additive: one new column plus constraints,
-- indexes and triggers; nothing from V1..V3 is renamed or removed.
--
-- The ticket's own Flyway line said V2, which V2__article_attempt.sql had already
-- taken by the time this was written. V4 is the next free number.

-- A revision's position in its document's history, 1 for the first observation.
--
-- fetched_at alone would be a weak order: two observations can share a timestamp,
-- and "version 3 of this article" is how a human addresses a revision, not
-- "the one fetched at 07:14:02.118443Z". The column exists mainly for the
-- unique constraint below, though.
alter table document_version
    add column version_number int;

-- Backfill by observation order for any row written before this migration. Rows that
-- share a fetched_at are ordered by id, which is arbitrary but stable, so re-running
-- the backfill on a restored dump produces the same numbering.
update document_version v
set version_number = ordered.position
from (select id,
             row_number() over (partition by document_id order by fetched_at, id) as position
      from document_version) ordered
where v.id = ordered.id;

alter table document_version
    alter column version_number set not null;

-- The invariant behind document.version_count: two writers appending the "next"
-- revision of one document concurrently both compute the same number, and exactly
-- one of them gets to commit it. Without this the loser would silently produce a
-- second version 3 and a version_count that no longer matches the history.
alter table document_version
    add constraint uq_document_version_number unique (document_id, version_number);

-- Ordering a document's history, and reading the revision that was current at a
-- given instant: both are (document_id, <ordering column>) range scans.
create index idx_document_version_document_number
    on document_version (document_id, version_number);

create index idx_document_version_document_fetched_at
    on document_version (document_id, fetched_at desc);

-- Append-only, enforced.
--
-- A document version is the evidence that an article said something at a point in
-- time. Evidence that can be edited afterwards is worth nothing, and this system's
-- whole output is the claim "the text used to read like this". The application never
-- updates or deletes a version -- there is no retention limit and no expiry -- so
-- these triggers cost nothing in normal operation and turn a future bug, a stray
-- migration or a hand-typed UPDATE into an error instead of quiet evidence tampering.
--
-- Row-level triggers, so truncate is deliberately not covered: dropping the whole
-- table is an administrative act (and how the integration tests reset), while
-- rewriting single rows is the thing that must never happen unnoticed.
create or replace function document_version_is_append_only() returns trigger
    language plpgsql as $$
begin
    raise exception
        'document_version is append-only: % on version % of document % is not allowed',
        tg_op, old.version_number, old.document_id
        using errcode = 'restrict_violation';
end;
$$;

create trigger trg_document_version_no_update
    before update on document_version
    for each row execute function document_version_is_append_only();

create trigger trg_document_version_no_delete
    before delete on document_version
    for each row execute function document_version_is_append_only();
