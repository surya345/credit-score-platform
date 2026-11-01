package dev.credit.reports;

import dev.credit.contracts.*;
import dev.credit.platform.JsonCodec;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ScoreListener {
  private final ReportService service;
  private final JsonCodec json;

  public ScoreListener(ReportService s, JsonCodec j) {
    service = s;
    json = j;
  }

  @KafkaListener(topics = Topics.SCORE_UPDATES)
  public void receive(String payload) {
    service.process(json.read(payload, ScoreCalculatedEvent.class));
  }
}
