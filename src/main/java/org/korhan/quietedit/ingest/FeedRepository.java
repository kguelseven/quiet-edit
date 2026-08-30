package org.korhan.quietedit.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeedRepository extends JpaRepository<Feed, UUID> {

    Optional<Feed> findByUrl(String url);

    /** Ordered so that a run -- and therefore its log and its tests -- is reproducible. */
    List<Feed> findByActiveTrueOrderByUrlAsc();
}
