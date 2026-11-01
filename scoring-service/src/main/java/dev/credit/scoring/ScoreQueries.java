package dev.credit.scoring;

import dev.credit.contracts.ScoreResult;
import dev.credit.platform.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ScoreQueries {
  public record ScoreView(
      String id,
      String customerId,
      String sourceEventId,
      long sourceVersion,
      Instant calculatedAt,
      ScoreResult result) {}

  private final StringRedisTemplate redis;
  private final JsonCodec json;
  private final boolean enabled;

  public ScoreQueries(
      StringRedisTemplate r, JsonCodec j, @Value("${app.cache.enabled:true}") boolean e) {
    redis = r;
    json = j;
    enabled = e;
  }

  public ScoreView view(CreditScore s) {
    String key = "credit:score:v1:" + s.id;
    if (enabled)
      try {
        String v = redis.opsForValue().get(key);
        if (v != null) return json.read(v, ScoreView.class);
      } catch (RuntimeException ignored) {
      }
    var view =
        new ScoreView(
            s.id,
            s.customerId,
            s.sourceEventId,
            s.sourceVersion,
            s.calculatedAt,
            json.read(s.resultJson, ScoreResult.class));
    if (enabled)
      try {
        redis.opsForValue().set(key, json.write(view), Duration.ofMinutes(10));
      } catch (RuntimeException ignored) {
      }
    return view;
  }
}
