package dev.credit.scoring;

import dev.credit.contracts.*;
import dev.credit.platform.ApiException;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {
  private final ScoreRepository scores;
  private final ScoreQueries queries;
  private final ScoringEngine engine;

  public ScoreController(ScoreRepository s, ScoreQueries q, ScoringEngine e) {
    scores = s;
    queries = q;
    engine = e;
  }

  @GetMapping("/customers/{id}/latest")
  public ScoreQueries.ScoreView latest(@PathVariable UUID id) {
    return queries.view(
        scores
            .findFirstByCustomerIdOrderBySourceVersionDesc(id.toString())
            .orElseThrow(
                () -> new ApiException(404, "No score yet. Submit financial data first.")));
  }

  @GetMapping("/customers/{id}/history")
  public Map<String, Object> history(
      @PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
    if (page < 0) throw new ApiException(400, "Invalid page.");
    var r =
        scores.findByCustomerIdOrderBySourceVersionDesc(id.toString(), PageRequest.of(page, 20));
    return Map.of(
        "items",
        r.getContent().stream().map(queries::view).toList(),
        "total",
        r.getTotalElements(),
        "page",
        page);
  }

  @PostMapping("/preview")
  public ScoreResult preview(@Valid @RequestBody FinancialInput input) {
    return engine.calculate(input);
  }
}
