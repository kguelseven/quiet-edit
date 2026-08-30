package org.korhan.quietedit.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChangeRepository extends JpaRepository<Change, UUID> {
}
