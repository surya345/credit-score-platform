package dev.credit.contracts;

import java.time.Instant;

public record ScoreCalculatedEvent(
    String eventId,
    int schemaVersion,
    String scoreId,
    String sourceEventId,
    String customerId,
    long sourceVersion,
    Instant calculatedAt,
    ScoreResult result) {}
