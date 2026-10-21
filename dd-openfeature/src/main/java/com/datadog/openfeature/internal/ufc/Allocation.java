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

  /** First activation of this allocation's randomization; null means unknown. */
  public final Instant randomizationActivatedAt;

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
    this.key = key;
    this.rules = rules;
    this.startAt = startAt;
    this.endAt = endAt;
    this.splits = splits;
    this.doLog = doLog;
    this.randomizationActivatedAt = randomizationActivatedAt;
  }
}
