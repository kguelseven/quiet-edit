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
     * Matched against identity <em>or</em> observed origin, because the URL the caller has
     * is a feed link resolved without a fetch, and under syndication that link is the
     * origin while the document is filed under another publisher's canonical URL.
     *
     * <p>A row can match on either column, so the caller decides which requested URL each
     * row answers for; see {@link DocumentRegistry#observationsOf}.
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
     * A pre-filter, not the decision: whether a document is actually due is the caller's
     * rule, and this query only keeps the rows that rule could still say yes to.
     *
     * <p>Two window bounds rather than one, and this is the part that has to be right. A
     * document that has stopped being watched keeps its old {@code lastCheckedAt} and so
     * sits at the front of this order forever; the narrow bound is what keeps those --
     * nearly all of them -- out of the limit. Documents that have changed keep the wide
     * bound, so none can be missed here, and the caller retires the ones past their window.
     *
     * @param checkedBefore     the newest {@code lastCheckedAt} still worth offering
     * @param stableWindowStart cut-off for documents with no observed change
     * @param widestWindowStart cut-off for documents that have changed at least once
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
     * Documents observed to change at least once, most recently changed first.
     *
     * <p>The newest revision is reached through a correlated {@code max} rather than
     * through {@code d.versionCount}: the counter is denormalised, and a listing that
     * silently returned no row for a document whose counter had drifted would hide exactly
     * the document worth seeing. {@code nulls last} and the id tie-break keep the order
     * total, so paging by limit is stable.
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
     * The same listing, narrowed by the filters the reading interface offers.
     *
     * <p>The feed predicate folds the optional value into a {@code coalesce} against the
     * column it filters. The obvious spelling, {@code :feedId is null or d.feedId =
     * :feedId}, does not work: Hibernate renders the parameter twice and the occurrence
     * standing alone in {@code ? is null} reaches Postgres untyped, which refuses the
     * statement. One occurrence, typed by the column beside it, compares the column with
     * itself when null -- every row, and so no filter at all.
     *
     * <p>{@code changedSince} and {@code minVersions} need no such trick: both have a
     * meaningful floor the caller can pass instead of a null.
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

