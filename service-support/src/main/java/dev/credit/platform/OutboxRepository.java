package dev.credit.platform;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface OutboxRepository extends JpaRepository<OutboxEntry, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from OutboxEntry o where o.publishedAt is null order by o.createdAt,o.id")
  List<OutboxEntry> lockPending(Pageable p);
}
