package org.korhan.quietedit.versioning;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves an observed article to the document it belongs to, creating that document the
 * first time its canonical URL is seen.
 *
 * <p>Identity, not versioning: whether the observation differs from the last one is the
 * version store's decision, which is why {@code versionCount} and {@code lastChangedAt}
 * stay untouched here.
 *
 * <p>It also reads that record back, because whoever decides when a document is looked at
 * again needs to know when it was last looked at; the alternative, handing the repository
 * to the ingest package, would put the entity's setters there too.
 *
 * <p>Lives in {@code versioning} rather than in {@code ingest} because the document table
 * is this package's.
 */
@Service
public class DocumentRegistry {

    private final DocumentRepository documents;

    public DocumentRegistry(DocumentRepository documents) {
        this.documents = documents;
    }

    /**
     * One short transaction per article: an ingest run spends most of its wall clock in
     * network I/O, and a transaction spanning the run would pin a connection for all of it.
     *
     * <p>The observed origin is written once and never overwritten. A document is always
     * discovered through a feed link, so the first value is the page the publisher's own
     * feed pointed at; in the run that first learns the origin the document is fetched from
     * both addresses, and letting the later win would flip the recorded origin back and
     * forth with the text.
     *
     * <p>Two accepted limits of write-once: a moved article leaves the origin pointing at a
     * URL that redirects or eventually 404s, which the attempt log's give-up rule handles,
     * and two feeds carrying two origins for one canonical URL still alternate. Closing the
     * second needs origin-level version rows rather than one column.
     *
     * @param observedOriginUrl already canonicalised, so that it compares equal to the
     *                          identity a feed link resolves to before any fetch
     */
    @Transactional
    public Registration register(String canonicalUrl, String observedOriginUrl, UUID feedId,
                                 Instant observedAt) {
        Optional<Document> existing = documents.findByCanonicalUrl(canonicalUrl);
        if (existing.isPresent()) {
            Document document = existing.get();
            document.setLastCheckedAt(observedAt);
            if (document.getObservedOriginUrl() == null) {
                document.setObservedOriginUrl(observedOriginUrl);
            }
            documents.save(document);
            return new Registration(document.getId(), false);
        }
        Document document = new Document(canonicalUrl, feedId, observedAt, observedAt);
        document.setObservedOriginUrl(observedOriginUrl);
        return new Registration(documents.save(document).getId(), true);
    }

    /**
     * Keyed by the URL asked for. URLs with no document are absent rather than mapped to a
     * placeholder, which would have to invent timestamps for them.
     *
     * <p>A URL matches by identity or by observed origin, which is what lets a caller
     * recognise a syndicated copy from the link its own feed advertises.
     *
     * <p>Identity wins where both could answer -- a URL that is one document's canonical
     * URL and another's origin -- because filing it under the document it identifies is the
     * answer that cannot be wrong. The order of the two passes guarantees that regardless
     * of row order, and {@code canonical_url} is unique, so no pass has a contest.
     */
    @Transactional(readOnly = true)
    public Map<String, DocumentObservation> observationsOf(Collection<String> urls) {
        if (urls.isEmpty()) {
            return Map.of();
        }
        Set<String> asked = urls instanceof Set<String> set ? set : new HashSet<>(urls);
        List<DocumentObservation> rows = documents.observationsOf(asked);
        Map<String, DocumentObservation> byUrl = new HashMap<>(rows.size());
        for (DocumentObservation observation : rows) {
            if (asked.contains(observation.canonicalUrl())) {
                byUrl.put(observation.canonicalUrl(), observation);
            }
        }
        for (DocumentObservation observation : rows) {
            if (observation.observedOriginUrl() != null && asked.contains(observation.observedOriginUrl())) {
                byUrl.putIfAbsent(observation.observedOriginUrl(), observation);
            }
        }
        return byUrl;
    }

    /**
     * A coarse filter with the decision left to the caller, which knows the re-check rule.
     * Ordering by {@code lastCheckedAt} is what makes the cap safe: a row the limit cuts
     * off is more recently checked than every row above it, so it comes back next run.
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
