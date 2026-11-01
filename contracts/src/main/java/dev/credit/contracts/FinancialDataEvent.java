package dev.credit.contracts;

import java.time.Instant;

public record FinancialDataEvent(
    String eventId,
    int schemaVersion,
    String customerId,
    long sourceVersion,
    Instant submittedAt,
    FinancialInput input,
    String officerId) {}
