package dev.credit.scoring;

import dev.credit.contracts.*;
import dev.credit.platform.JsonCodec;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FinancialListener {
  private final ScoringService service;
  private final JsonCodec json;

  public FinancialListener(ScoringService s, JsonCodec j) {
    service = s;
    json = j;
  }

  @KafkaListener(topics = Topics.FINANCIAL_DATA)
  public void receive(String payload) {
    service.process(json.read(payload, FinancialDataEvent.class));
  }
}
