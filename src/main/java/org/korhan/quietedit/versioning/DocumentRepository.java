package org.korhan.quietedit.versioning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByCanonicalUrl(String canonicalUrl);

    List<Document> findByCanonicalUrlIn(Collection<String> canonicalUrls);
}
