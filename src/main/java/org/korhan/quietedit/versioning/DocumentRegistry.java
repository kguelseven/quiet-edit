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
     *
     * <p>The observed origin is written once and never overwritten. A document is
     * always discovered through a feed link, so the first value is the page a
     * publisher's own feed pointed at, and that is the page a re-check should keep
     * asking for. Overwriting it would defeat the point: in the run that first learns
     * the origin the document is still fetched from both addresses, and letting the
     * later of the two win would just flip the recorded origin back and forth with the
     * text.
     *
     * <p>Two known limits of write-once, both accepted here. A publisher that moves
     * its article leaves the origin pointing at a URL that redirects -- which the
     * fetcher follows -- or eventually 404s, and a link that keeps failing is dropped
     * by the attempt log's give-up rule rather than retried forever. And if two feeds
     * carry two different origins for one canonical URL, only one of them is recorded;
     * the other feed's link is still fetched under the same identity, so that case
     * still alternates. No feed in the current catalogue does it, and closing it needs
     * origin-level version rows rather than one column.
     *
     * @param observedOriginUrl the page this observation's text was fetched from,
     *                          already canonicalised so that it compares equal to the
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
     * What is known about each of these URLs, keyed by the URL asked for. URLs with no
     * document are absent from the result rather than mapped to a placeholder: "we
     * have never seen this" is the caller's own concept, and a placeholder would have
     * to invent timestamps for it.
     *
     * <p>A URL matches a document by identity or by observed origin, which is what
     * lets a caller recognise a syndicated copy from the link its own feed advertises
     * -- that link is the origin, and the document is filed under the other
     * publisher's canonical URL.
     *
     * <p>Identity wins where both could answer, in the one case where they disagree: a
     * URL that is one document's canonical URL and another document's origin. Filing it
     * under the document it identifies is the answer that cannot be wrong, and the
     * order of the two passes below is what guarantees it regardless of row order.
     * {@code canonical_url} is unique, so there is never a contest within a pass.
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
