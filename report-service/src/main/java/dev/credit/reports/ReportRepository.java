package dev.credit.reports;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<CreditReport, String> {
  boolean existsByEventId(String id);

  Page<CreditReport> findByCustomerIdOrderBySourceVersionDesc(String id, Pageable p);
}
