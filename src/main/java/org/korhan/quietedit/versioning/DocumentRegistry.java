package org.korhan.quietedit.versioning;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an observed article to the document it belongs to, creating that
 * document the first time its canonical URL is seen.
 *
 * <p>This is identity, not versioning: it decides <em>which</em> article an
 * observation is about and records that the article was looked at. Whether the
 * observation differs from the last one -- and therefore whether a new revision
 * has to be appended -- is the version store's decision, and {@code versionCount}
 * and {@code lastChangedAt} stay untouched here for exactly that reason.
 *
 * <p>Lives in {@code versioning} rather than in {@code ingest} because the document
 * table is this package's, and because the version store will grow from here rather
 * than beside it.
 */
@Service
public class DocumentRegistry {

    private final DocumentRepository documents;

    public DocumentRegistry(DocumentRepository documents) {
        this.documents = documents;
    }

    /**
     * One short transaction per article on purpose: an ingest run spends most of its
     * wall clock in network I/O, and a transaction spanning the run would pin a
     * connection for all of it while making one unusable document cost every other.
     */
    @Transactional
    public Registration register(String canonicalUrl, UUID feedId, Instant observedAt) {
        Optional<Document> existing = documents.findByCanonicalUrl(canonicalUrl);
        if (existing.isPresent()) {
            Document document = existing.get();
            document.setLastCheckedAt(observedAt);
            documents.save(document);
            return new Registration(document.getId(), false);
        }
        Document document = documents.save(new Document(canonicalUrl, feedId, observedAt, observedAt));
        return new Registration(document.getId(), true);
    }

    /** @param created true when this observation is the first one of that document */
    public record Registration(UUID documentId, boolean created) {
    }
}
