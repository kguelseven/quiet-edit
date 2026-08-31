package org.korhan.quietedit.versioning;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * <p>It also reads that record back. Whoever decides when a document is looked at
 * again needs to know when it was last looked at, and the alternative -- handing the
 * document repository to the ingest package -- would put the entity's setters there
 * too. What it hands out is {@link DocumentObservation}, never the entity.
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

    /**
     * What is known about each of these canonical URLs, keyed by URL. URLs with no
     * document are absent from the result rather than mapped to a placeholder: "we
     * have never seen this" is the caller's own concept, and a placeholder would have
     * to invent timestamps for it.
     */
    @Transactional(readOnly = true)
    public Map<String, DocumentObservation> observationsOf(Collection<String> canonicalUrls) {
        if (canonicalUrls.isEmpty()) {
            return Map.of();
        }
        return documents.observationsOf(canonicalUrls).stream()
                .collect(Collectors.toMap(DocumentObservation::canonicalUrl, observation -> observation));
    }

    /**
     * Documents that may be due for another look, most overdue first, capped at
     * {@code limit}.
     *
     * <p>Deliberately a coarse filter with the decision left to the caller: when a
     * document is fetched again is the re-check policy's rule, and this class knows
     * nothing about it beyond the three bounds it is handed. Ordering by
     * {@code lastCheckedAt} is what makes the cap safe -- a row the limit cuts off is
     * more recently checked than every row above it, so it comes back on the next run
     * rather than being lost.
     */
    @Transactional(readOnly = true)
    public List<DocumentObservation> observationsPossiblyDue(Instant checkedBefore, Instant stableWindowStart,
                                                             Instant widestWindowStart, int limit) {
        return documents.observationsPossiblyDue(checkedBefore, stableWindowStart, widestWindowStart,
                Limit.of(limit));
    }

    /** @param created true when this observation is the first one of that document */
    public record Registration(UUID documentId, boolean created) {
    }
}
