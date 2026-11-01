package dev.credit.platform;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, String> {
  Page<AuditEntry> findAllByOrderByOccurredAtDesc(Pageable p);
}
