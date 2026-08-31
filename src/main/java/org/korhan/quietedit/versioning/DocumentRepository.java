package org.korhan.quietedit.versioning;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByCanonicalUrl(String canonicalUrl);

    /**
     * What is known about each of these canonical URLs. One query rather than a
     * lookup per URL: the caller decides a whole run's candidates before fetching any
     * of them.
     */
    @Query("""
            select new org.korhan.quietedit.versioning.DocumentObservation(
                d.id, d.canonicalUrl, d.feedId, d.firstSeenAt, d.lastCheckedAt, d.lastChangedAt, d.versionCount)
            from Document d
            where d.canonicalUrl in :canonicalUrls
            """)
    List<DocumentObservation> observationsOf(@Param("canonicalUrls") Collection<String> canonicalUrls);

    /**
     * Documents that could still be due for another look, most overdue first.
     *
     * <p>A pre-filter, not the decision: whether a document is actually due, and when,
     * is the caller's rule. This query only keeps the rows that rule could still say
     * yes to -- checked longer ago than the shortest interval it uses, and touched
     * recently enough to still be watched. {@code coalesce} is what makes "touched" one
     * expression: a document never observed to change was last touched when it was
     * discovered.
     *
     * <p>Two window bounds rather than one, and this is the part that has to be right.
     * A document that has stopped being watched keeps the {@code lastCheckedAt} it had
     * when it was last fetched, so under {@code order by lastCheckedAt} it sits at the
     * very front of this result forever -- and with one bound wide enough for the
     * longest-watched document, enough such rows would fill {@code limit} and crowd out
     * every document that really was due. Applying the narrow bound to documents that
     * have never been observed to change -- which is nearly all of them, and all of the
     * ones that pile up -- keeps them out. Documents that have changed keep the wide
     * bound, so none of them can be missed here; there are few of them, and the caller
     * retires whichever of them are past their own window.
     *
     * @param checkedBefore     the newest {@code lastCheckedAt} still worth offering
     * @param stableWindowStart cut-off for documents with no observed change
     * @param widestWindowStart cut-off for documents that have changed at least once,
     *                          set to the longest window any of them could earn
     */
    @Query("""
            select new org.korhan.quietedit.versioning.DocumentObservation(
                d.id, d.canonicalUrl, d.feedId, d.firstSeenAt, d.lastCheckedAt, d.lastChangedAt, d.versionCount)
            from Document d
            where d.lastCheckedAt < :checkedBefore
              and (coalesce(d.lastChangedAt, d.firstSeenAt) > :stableWindowStart
                   or (d.versionCount > 1
                       and coalesce(d.lastChangedAt, d.firstSeenAt) > :widestWindowStart))
            order by d.lastCheckedAt asc
            """)
    List<DocumentObservation> observationsPossiblyDue(@Param("checkedBefore") Instant checkedBefore,
                                                      @Param("stableWindowStart") Instant stableWindowStart,
                                                      @Param("widestWindowStart") Instant widestWindowStart,
                                                      Limit limit);
}
