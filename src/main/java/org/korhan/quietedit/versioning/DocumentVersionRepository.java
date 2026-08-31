package org.korhan.quietedit.versioning;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading the history of a document. Every query orders by {@code versionNumber}
 * rather than by {@code fetchedAt}: the ordinal is unique per document, so the order
 * is total and does not depend on the resolution of the clock that stamped two
 * observations.
 */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    /** The document's whole history, oldest first. No paging: history is what it is. */
    List<DocumentVersion> findByDocumentIdOrderByVersionNumberAsc(UUID documentId);

    /** One addressable revision -- "version 3 of this article". */
    Optional<DocumentVersion> findByDocumentIdAndVersionNumber(UUID documentId, int versionNumber);

    /** The revision an append is compared against. */
    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    /**
     * The revision that was current at {@code instant}: the newest observation not
     * later than it. Returns a list with {@code Limit.of(1)} rather than an
     * {@code Optional} because the limit is the caller's, not the query's.
     *
     * <p>Ordered by {@code fetchedAt} first because the question is about time, and
     * the ordinal only breaks the tie when two observations share a timestamp.
     */
    @Query("""
            select v from DocumentVersion v
            where v.documentId = :documentId and v.fetchedAt <= :instant
            order by v.fetchedAt desc, v.versionNumber desc
            """)
    List<DocumentVersion> findCurrentAt(@Param("documentId") UUID documentId,
                                        @Param("instant") Instant instant,
                                        Limit limit);
}
