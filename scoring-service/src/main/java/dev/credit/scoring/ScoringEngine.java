package dev.credit.scoring;

import dev.credit.contracts.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ScoringEngine {
  public ScoreResult calculate(FinancialInput i) {
    BigDecimal debt =
        i.monthlyDebtPayments()
            .divide(i.monthlyIncome(), 10, RoundingMode.HALF_UP)
            .min(BigDecimal.ONE);
    Map<String, Integer> p = new LinkedHashMap<>();
    p.put("Payment history", Math.max(0, 200 - i.missedPaymentsLast12Months() * 40));
    p.put(
        "Credit utilization",
        BigDecimal.valueOf(100)
            .subtract(i.creditUtilizationPercent())
            .multiply(new BigDecimal("1.5"))
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact());
    p.put(
        "Debt affordability",
        BigDecimal.ONE
            .subtract(debt)
            .multiply(BigDecimal.valueOf(100))
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact());
    p.put(
        "History length",
        BigDecimal.valueOf(Math.min(120, i.creditHistoryMonths()))
            .multiply(new BigDecimal("0.625"))
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact());
    p.put("Recent inquiries", Math.max(0, 25 - i.recentCreditInquiries() * 5));
    int score = 300 + p.values().stream().mapToInt(Integer::intValue).sum();
    List<String> notes = new ArrayList<>();
    if (i.missedPaymentsLast12Months() > 0)
      notes.add("Missed payments reduced the payment-history contribution.");
    if (i.creditUtilizationPercent().compareTo(new BigDecimal("30")) > 0)
      notes.add("Utilization exceeds the demo model's 30% observation threshold.");
    if (debt.compareTo(new BigDecimal("0.4")) > 0)
      notes.add("Monthly debt payments exceed 40% of monthly income.");
    if (i.creditHistoryMonths() < 24) notes.add("Credit history is shorter than two years.");
    if (i.recentCreditInquiries() > 2)
      notes.add("Several recent inquiries reduced the model's contribution.");
    if (notes.isEmpty()) notes.add("No observation thresholds were exceeded in this demo model.");
    return new ScoreResult(
        score,
        score >= 750 ? "LOW" : score >= 650 ? "MODERATE" : "HIGH",
        "educational-v1",
        p,
        notes);
  }
}
