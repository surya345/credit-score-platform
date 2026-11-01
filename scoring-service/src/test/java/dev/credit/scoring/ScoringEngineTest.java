package dev.credit.scoring;

import static org.assertj.core.api.Assertions.*;

import dev.credit.contracts.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ScoringEngineTest {
  private final ScoringEngine engine = new ScoringEngine();

  private FinancialInput input(
      String debt, String utilization, int missed, int months, int inquiries) {
    return new FinancialInput(
        new BigDecimal("80000"),
        new BigDecimal(debt),
        new BigDecimal(utilization),
        missed,
        months,
        inquiries,
        "INR");
  }

  @Test
  void documentedExampleScores763() {
    var r = engine.calculate(input("12000", "25", 0, 72, 1));
    assertThat(r.score()).isEqualTo(763);
    assertThat(r.riskBand()).isEqualTo("LOW");
    assertThat(r.contributions().values().stream().mapToInt(Integer::intValue).sum() + 300)
        .isEqualTo(r.score());
  }

  @Test
  void strongestInputsReach850() {
    assertThat(engine.calculate(input("0", "0", 0, 120, 0)).score()).isEqualTo(850);
  }

  @Test
  void weakestInputsStayAt300() {
    assertThat(engine.calculate(input("160000", "100", 100, 0, 100)).score()).isEqualTo(300);
  }

  @Test
  void historyBenefitIsCapped() {
    assertThat(engine.calculate(input("12000", "25", 0, 960, 1)).score())
        .isEqualTo(engine.calculate(input("12000", "25", 0, 120, 1)).score());
  }

  @Test
  void moreDebtNeverImprovesScore() {
    int previous = 851;
    for (int d = 0; d <= 160000; d += 1000) {
      int score = engine.calculate(input(Integer.toString(d), "25", 0, 72, 1)).score();
      assertThat(score).isLessThanOrEqualTo(previous);
      previous = score;
    }
  }

  @Test
  void missedPaymentsNeverImproveScore() {
    int previous = 851;
    for (int m = 0; m <= 100; m++) {
      int score = engine.calculate(input("12000", "25", m, 72, 1)).score();
      assertThat(score).isLessThanOrEqualTo(previous);
      previous = score;
    }
  }
}
