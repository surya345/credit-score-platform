package dev.credit.platform;

import dev.credit.contracts.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {
  @Bean
  KafkaAdmin.NewTopics topics() {
    return new KafkaAdmin.NewTopics(
        TopicBuilder.name(Topics.FINANCIAL_DATA).partitions(3).replicas(1).build(),
        TopicBuilder.name(Topics.SCORE_UPDATES).partitions(3).replicas(1).build(),
        TopicBuilder.name(Topics.FINANCIAL_DATA + ".DLT").partitions(3).replicas(1).build(),
        TopicBuilder.name(Topics.SCORE_UPDATES + ".DLT").partitions(3).replicas(1).build());
  }

  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
    var r =
        new DeadLetterPublishingRecoverer(
            kafka,
            (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    r.setFailIfSendResultIsError(true);
    return new DefaultErrorHandler(r, new FixedBackOff(1000, 3));
  }
}
