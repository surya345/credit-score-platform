package dev.credit.contracts;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record FinancialInput(
    @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2)
        BigDecimal monthlyIncome,
    @NotNull @DecimalMin("0.00") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2)
        BigDecimal monthlyDebtPayments,
    @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal creditUtilizationPercent,
    @NotNull @Min(0) @Max(100) Integer missedPaymentsLast12Months,
    @NotNull @Min(0) @Max(960) Integer creditHistoryMonths,
    @NotNull @Min(0) @Max(100) Integer recentCreditInquiries,
    @NotBlank @Pattern(regexp = "INR|SEK|EUR|USD") String currency) {}
