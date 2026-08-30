package org.korhan.quietedit.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FeedRepository extends JpaRepository<Feed, UUID> {
}
