package org.korhan.quietedit.versioning;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The permanent record of what a document said, and when.
 *
 * <p>Every revision is stored whole, never as a delta: deltas make two versions
 * comparable only by replaying every revision between them, and one lost link makes every
 * later revision unreadable. Since Flyway V4 the table itself rejects every update and
 * delete.
 *
 * <p>An observation becomes a revision only when its hash differs from the newest stored
 * revision -- the newest only, not the whole history -- because a re-fetch of unchanged
 * text is the common case, while an article that goes A, B, A really did change twice and
 * a returning wording is the clearest signal this system has that an edit was
 * reconsidered, which is why Flyway V5 relaxed the unique
 * {@code (document_id, content_hash)} to a plain index.
 *
 * <p>The document counters are written in the same transaction as the version, and
 * {@code versionCount} is set to the new revision's ordinal rather than incremented, so
 * it equals the highest {@code versionNumber} by construction; {@code lastChangedAt}
 * stays null for a first revision, which the re-check policy reads as "never seen to
 * change".
 */
@Service
public class VersionStore {

    private final DocumentVersionRepository versions;
    private final DocumentRepository documents;
    private final ContentHasher hasher;

    public VersionStore(DocumentVersionRepository versions, DocumentRepository documents,
                        ContentHasher hasher) {
        this.versions = versions;
        this.documents = documents;
        this.hasher = hasher;
    }

    /**
     * One short transaction per observation, for the same reason {@link DocumentRegistry}
     * uses one: a run spends its wall clock on the network.
     *
     * @throws IllegalArgumentException if the document does not exist -- identity is
     *                                  established before versioning, so that is a defect
     */
    @Transactional
    public Stored record(UUID documentId, Observation observation) {
        String contentHash = hasher.hash(observation.content());
        Optional<DocumentVersion> latest = versions.findFirstByDocumentIdOrderByVersionNumberDesc(documentId);

        if (latest.isPresent() && latest.get().getContentHash().equals(contentHash)) {
            return Stored.unchanged(latest.get());
        }
        return Stored.appended(append(documentId, observation, contentHash, latest.orElse(null)),
                latest.map(DocumentVersion::getId).orElse(null));
    }

    private DocumentVersion append(UUID documentId, Observation observation, String contentHash,
                                   DocumentVersion previous) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("No document " + documentId));

        int versionNumber = previous == null ? 1 : previous.getVersionNumber() + 1;
        DocumentVersion version = new DocumentVersion(
                documentId,
                versionNumber,
                observation.fetchedAt(),
                observation.content().paragraphs(),
                contentHash,
                observation.httpStatus());
        version.setPageTitle(observation.content().title());
        version.setFeedTitle(observation.feedTitle());
        version.setRawHtmlRef(observation.rawHtmlRef());
        version.setPublishedAt(observation.publishedAt());
        version.setPublishedAtExact(observation.publishedAtExact());
        version.setEncoding(observation.encoding());
        DocumentVersion saved = versions.save(version);

        document.setVersionCount(versionNumber);
        if (previous != null) {
            document.setLastChangedAt(observation.fetchedAt());
        }
        documents.save(document);
        return saved;
    }

    /** The document's whole history, oldest first. */
    @Transactional(readOnly = true)
    public List<DocumentVersion> history(UUID documentId) {
        return versions.findByDocumentIdOrderByVersionNumberAsc(documentId);
    }

    /**
     * Together with {@link #history} this is what makes <em>any</em> two revisions
     * comparable and not just adjacent ones: both are read in full and independently.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentVersion> version(UUID documentId, int versionNumber) {
        return versions.findByDocumentIdAndVersionNumber(documentId, versionNumber);
    }

    /** The newest revision, which is the one a fresh observation is compared against. */
    @Transactional(readOnly = true)
    public Optional<DocumentVersion> latest(UUID documentId) {
        return versions.findFirstByDocumentIdOrderByVersionNumberDesc(documentId);
    }

    /**
     * "Current" is by observation time, not publication time: the store knows when it
     * looked, never when the publisher pressed save.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentVersion> asOf(UUID documentId, Instant instant) {
        return versions.findCurrentAt(documentId, instant, Limit.of(1)).stream().findFirst();
    }

    /**
     * @param versionId         the revision this observation is now represented by: the
     *                          appended one, or the newest existing one
     * @param previousVersionId the revision that was newest before an append, null when
     *                          this was the first or when nothing was written
     */
    public record Stored(
            UUID versionId,
            int versionNumber,
            String contentHash,
            VersionOutcome outcome,
            UUID previousVersionId) {

        static Stored appended(DocumentVersion version, UUID previousVersionId) {
            return new Stored(version.getId(), version.getVersionNumber(), version.getContentHash(),
                    VersionOutcome.APPENDED, previousVersionId);
        }

        static Stored unchanged(DocumentVersion latest) {
            return new Stored(latest.getId(), latest.getVersionNumber(), latest.getContentHash(),
                    VersionOutcome.UNCHANGED, null);
        }

        public boolean appended() {
            return outcome == VersionOutcome.APPENDED;
        }
    }
}
