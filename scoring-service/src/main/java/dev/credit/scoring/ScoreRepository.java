package dev.credit.scoring;

import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoreRepository extends JpaRepository<CreditScore, String> {
  boolean existsBySourceEventId(String eventId);

  Optional<CreditScore> findFirstByCustomerIdOrderBySourceVersionDesc(String id);

  Page<CreditScore> findByCustomerIdOrderBySourceVersionDesc(String id, Pageable p);
}
