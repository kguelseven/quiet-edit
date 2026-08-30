-- Records how a version's bytes were decoded. Additive: three new nullable-or-defaulted
-- columns on document_version, nothing in V1 or V2 renamed or removed.
--
-- The point of the migration is encoding_replaced. A body whose declared charset its
-- own bytes contradict is decoded with U+FFFD substituted, and from that moment the
-- mojibake is indistinguishable from prose: it hashes as prose and versions as prose.
-- The day the publisher fixes their charset header, the next observation differs from
-- the last one in every affected character, and a change detector with no memory of the
-- substitution can only report a rewrite. This column is that memory.
--
-- charset and charset_source are nullable because a version may predate the verdict.
-- encoding_replaced is not null with a false default instead, so an unrecorded row
-- reads as "the text was clean" -- the only safe default, since the alternative marks
-- every historical version as suspect.
alter table document_version
    add column charset           text,
    -- BOM | HTTP_HEADER | DOCUMENT | DEFAULT -- which declaration won, which is what
    -- says how much the charset is worth when a later observation disagrees.
    add column charset_source    text,
    add column encoding_replaced boolean not null default false;

-- The operational query this exists for: which versions are mojibake and need a
-- second look. Partial, because the true rows are the rare ones.
create index idx_document_version_encoding_replaced
    on document_version (document_id)
    where encoding_replaced;

-- change.classification gains the value ENCODING_REPAIR. No DDL: the column is text
-- and unconstrained, and V1's comment listing the three original values is left alone
-- because editing an applied migration would break Flyway's checksum.
