-- Lets a document return to a wording it already published.
--
-- V1 made (document_id, content_hash) unique to guarantee "identical content
-- produces no new version". The guarantee was real but it was enforced in the wrong
-- place: an article that goes A, B, A really did change twice, and the returning
-- revision collided with the row it returns to, so the one behaviour this system
-- exists to catch was the one it could not record. The rule itself belongs in
-- VersionStore, where it is a comparison against the *newest* revision only, and it
-- stays there unchanged.
--
-- Identity of a revision does not depend on this constraint: (document_id,
-- version_number) has been unique since V4, and that is what makes a concurrent
-- double-append a failed insert.
--
-- The ticket (quietedit-cca.2) names no Flyway version. V5 is the next free number.
alter table document_version
    drop constraint uq_document_version_document_content;

-- Kept as a plain index, because the lookup the constraint also served -- "has this
-- document ever said this?" -- is what a revert-aware history reads, and V1's
-- content-hash index is document-blind and therefore the wrong shape for it.
create index idx_document_version_document_content
    on document_version (document_id, content_hash);
