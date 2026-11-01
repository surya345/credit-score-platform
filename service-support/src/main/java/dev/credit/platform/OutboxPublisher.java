package dev.credit.platform;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
  private final OutboxRepository outbox;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate tx;
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  public OutboxPublisher(
      OutboxRepository o, KafkaTemplate<String, String> k, TransactionTemplate t) {
    outbox = o;
    kafka = k;
    tx = t;
  }

  @Scheduled(fixedDelayString = "${app.events.publish-delay-ms:1000}")
  public void publish() {
    tx.executeWithoutResult(
        status -> {
          for (var e : outbox.lockPending(PageRequest.of(0, 20))) {
            e.attempts++;
            try {
              kafka.send(e.topic, e.messageKey, e.payload).get(10, TimeUnit.SECONDS);
              e.publishedAt = Instant.now();
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              break;
            } catch (Exception ex) {
              log.warn("Outbox retry pending eventId={} attempt={}", e.id, e.attempts);
              break;
            }
          }
        });
  }
}
