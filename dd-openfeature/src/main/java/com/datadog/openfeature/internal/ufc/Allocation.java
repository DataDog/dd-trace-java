package com.datadog.openfeature.internal.ufc;

import java.time.Instant;
import java.util.List;

public final class Allocation {
  public final String key;
  public final List<Rule> rules;
  public final Instant startAt;
  public final Instant endAt;
  public final List<Split> splits;
  public final Boolean doLog;

  /** First activation of this allocation's randomization; null means unknown or malformed. */
  public final Instant randomizationActivatedAt;

  /** Distinguishes malformed activation from missing/null for future-mode evaluation errors. */
  public final boolean invalidRandomizationActivation;

  public Allocation(
      final String key,
      final List<Rule> rules,
      final Instant startAt,
      final Instant endAt,
      final List<Split> splits,
      final Boolean doLog) {
    this(key, rules, startAt, endAt, splits, doLog, null);
  }

  public Allocation(
      final String key,
      final List<Rule> rules,
      final Instant startAt,
      final Instant endAt,
      final List<Split> splits,
      final Boolean doLog,
      final Instant randomizationActivatedAt) {
    this(key, rules, startAt, endAt, splits, doLog, randomizationActivatedAt, false);
  }

  public Allocation(
      final String key,
      final List<Rule> rules,
      final Instant startAt,
      final Instant endAt,
      final List<Split> splits,
      final Boolean doLog,
      final Instant randomizationActivatedAt,
      final boolean invalidRandomizationActivation) {
    this.key = key;
    this.rules = rules;
    this.startAt = startAt;
    this.endAt = endAt;
    this.splits = splits;
    this.doLog = doLog;
    this.randomizationActivatedAt = randomizationActivatedAt;
    this.invalidRandomizationActivation = invalidRandomizationActivation;
  }
}
