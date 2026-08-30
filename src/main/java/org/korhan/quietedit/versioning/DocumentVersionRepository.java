package org.korhan.quietedit.versioning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {
}
