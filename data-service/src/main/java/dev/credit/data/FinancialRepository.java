package dev.credit.data;

import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialRepository extends JpaRepository<FinancialRecord, Long> {
  Optional<FinancialRecord> findByEventId(String eventId);

  Page<FinancialRecord> findByCustomerIdOrderByIdDesc(String customerId, Pageable p);
}
