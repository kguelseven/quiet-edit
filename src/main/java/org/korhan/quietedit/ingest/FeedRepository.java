package org.korhan.quietedit.ingest;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeedRepository extends JpaRepository<Feed, UUID> {

    Optional<Feed> findByUrl(String url);

    /** Ordered so that a run -- and therefore its log and its tests -- is reproducible. */
    List<Feed> findByActiveTrueOrderByUrlAsc();

    /**
     * The feeds that have produced the most documents, with their counts and their last
     * poll -- one aggregate over the whole table, never a count per feed.
     *
     * <p>{@code left join} rather than an inner one, and the join is written out because
     * {@code Document} holds its feed as a plain id rather than an association. Left, so
     * a feed with no documents at all still appears, with a zero: an operator scanning
     * for a stalled source is looking for precisely that row, and an inner join would
     * drop it.
     *
     * <p>The name breaks the ordering tie so the top of the list does not reshuffle
     * between two requests when several feeds sit at the same count.
     */
    @Query("""
            select new org.korhan.quietedit.ingest.FeedCoverage(
                f.id, f.name, count(d.id), f.lastPolledAt)
            from Feed f
              left join Document d on d.feedId = f.id
            group by f.id, f.name, f.lastPolledAt
            order by count(d.id) desc, f.name asc
            """)
    List<FeedCoverage> coverage(Limit limit);
}
