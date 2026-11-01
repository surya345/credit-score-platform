package dev.credit.contracts;

import java.util.*;

public record ScoreResult(
    int score,
    String riskBand,
    String algorithmVersion,
    Map<String, Integer> contributions,
    List<String> observations) {}
