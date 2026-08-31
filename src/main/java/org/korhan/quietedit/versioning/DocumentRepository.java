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
     * What is known about each of these URLs, matched against identity <em>or</em>
     * observed origin. One query rather than a lookup per URL: the caller decides a
     * whole run's candidates before fetching any of them.
     *
     * <p>Both columns, because the URL the caller has is a feed link resolved without
     * a fetch, and under syndication that link is the origin while the document is
     * filed under another publisher's canonical URL. Matching identity alone made such
     * a link look unseen, which is what let one document be fetched twice in one run.
     *
     * <p>A row can match on either column, so the caller -- not this query -- decides
     * which requested URL each row answers for; see
     * {@link DocumentRegistry#observationsOf}.
     */
    @Query("""
            select new org.korhan.quietedit.versioning.DocumentObservation(
                d.id, d.canonicalUrl, d.observedOriginUrl, d.feedId,
                d.firstSeenAt, d.lastCheckedAt, d.lastChangedAt, d.versionCount)
            from Document d
            where d.canonicalUrl in :urls
               or d.observedOriginUrl in :urls
            """)
    List<DocumentObservation> observationsOf(@Param("urls") Collection<String> urls);

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
                d.id, d.canonicalUrl, d.observedOriginUrl, d.feedId,
                d.firstSeenAt, d.lastCheckedAt, d.lastChangedAt, d.versionCount)
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

    /**
     * Documents observed to change at least once, most recently changed first -- the
     * list an operator scans to find an edit worth looking at.
     *
     * <p>{@code versionCount > 1} rather than {@code lastChangedAt is not null} because
     * the count is what the version store sets by construction from the revision's own
     * ordinal; the timestamp is set in the same transaction, so the two agree, and the
     * count is the condition the ticket asks for.
     *
     * <p>The entity join reaches the newest revision through a correlated {@code max}
     * rather than through {@code d.versionCount}: the counter is denormalised, and a
     * listing that silently returned no row for a document whose counter had drifted
     * would hide exactly the document worth seeing. {@code nulls last} and the id
     * tie-break keep the order total, so paging by limit is stable.
     */
    @Query("""
            select new org.korhan.quietedit.versioning.DocumentHistorySummary(
                d.id, d.canonicalUrl, d.feedId, d.firstSeenAt, d.lastCheckedAt, d.lastChangedAt,
                d.versionCount, v.versionNumber, v.pageTitle)
            from Document d
              join DocumentVersion v on v.documentId = d.id
            where d.versionCount > 1
              and v.versionNumber = (select max(v2.versionNumber)
                                     from DocumentVersion v2
                                     where v2.documentId = d.id)
            order by d.lastChangedAt desc nulls last, d.id asc
            """)
    List<DocumentHistorySummary> changedDocuments(Limit limit);

    /**
     * The same listing, narrowed by the filters the reading interface offers, with the
     * newest revision itself rather than a summary of it.
     *
     * <p>One query with three predicates rather than a {@code Specification} or a method
     * per combination: the combinations are what the interface actually asks for, and
     * all three are cheap to express as long as no parameter is ever compared against
     * nothing.
     *
     * <p>That last part is the reason for the shape of the feed predicate.
     * {@code :feedId is null or d.feedId = :feedId} is the obvious spelling and it does
     * not work: Hibernate renders the parameter twice, and the occurrence standing alone
     * in {@code ? is null} reaches Postgres with no type context, which refuses the
     * statement with "could not determine data type of parameter". Folding the optional
     * value into a {@code coalesce} against the column it filters leaves one occurrence,
     * typed by the column beside it, and a null then compares the column with itself --
     * which is every row, and so no filter at all.
     *
     * <p>{@code changedSince} needs no such trick because it has a lower bound that is
     * always meaningful: the caller passes the epoch for "any time" rather than a null.
     * {@code minVersions} likewise has a floor -- a document with one revision has not
     * changed, which is the {@code versionCount > 1} this listing is defined by -- so a
     * lower value cannot widen it.
     *
     * @param feedId       keep only documents of this feed, or null for all feeds
     * @param changedSince keep only documents touched at or after this instant
     * @param minVersions  keep only documents with at least this many revisions
     */
    @Query("""
            select new org.korhan.quietedit.versioning.ChangedDocumentRow(d, v)
            from Document d
              join DocumentVersion v on v.documentId = d.id
            where d.versionCount > 1
              and v.versionNumber = (select max(v2.versionNumber)
                                     from DocumentVersion v2
                                     where v2.documentId = d.id)
              and d.feedId = coalesce(:feedId, d.feedId)
              and coalesce(d.lastChangedAt, d.firstSeenAt) >= :changedSince
              and d.versionCount >= :minVersions
            order by d.lastChangedAt desc nulls last, d.id asc
            """)
    List<ChangedDocumentRow> changedDocumentsFiltered(@Param("feedId") UUID feedId,
                                                      @Param("changedSince") Instant changedSince,
                                                      @Param("minVersions") int minVersions,
                                                      Limit limit);
}

