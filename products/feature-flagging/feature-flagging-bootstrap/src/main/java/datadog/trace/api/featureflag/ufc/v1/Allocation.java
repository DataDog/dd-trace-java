package datadog.trace.api.featureflag.ufc.v1;

import java.time.Instant;
import java.util.Date;
import java.util.List;

public class Allocation {
  public final String key;
  public final List<Rule> rules;
  public final Date startAt;
  public final Date endAt;
  public final List<Split> splits;
  public final Boolean doLog;

  /** First activation of this allocation's randomization; null means unknown. */
  public final Instant randomizationActivatedAt;

  /** Invalid activation is retained for future-mode evaluation errors. */
  public final boolean invalidRandomizationActivation;

  private final transient Instant preciseStartAt;
  private final transient Instant preciseEndAt;

  public Allocation(
      final String key,
      final List<Rule> rules,
      final Date startAt,
      final Date endAt,
      final List<Split> splits,
      final Boolean doLog) {
    this(
        key,
        rules,
        startAt,
        endAt,
        splits,
        doLog,
        startAt == null ? null : startAt.toInstant(),
        endAt == null ? null : endAt.toInstant(),
        null,
        false);
  }

  private Allocation(
      final String key,
      final List<Rule> rules,
      final Date startAt,
      final Date endAt,
      final List<Split> splits,
      final Boolean doLog,
      final Instant preciseStartAt,
      final Instant preciseEndAt,
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
    this.preciseStartAt = preciseStartAt;
    this.preciseEndAt = preciseEndAt;
  }

  public static Allocation fromInstants(
      final String key,
      final List<Rule> rules,
      final Instant startAt,
      final Instant endAt,
      final List<Split> splits,
      final Boolean doLog) {
    return fromInstants(key, rules, startAt, endAt, splits, doLog, null);
  }

  public static Allocation fromInstants(
      final String key,
      final List<Rule> rules,
      final Instant startAt,
      final Instant endAt,
      final List<Split> splits,
      final Boolean doLog,
      final Instant randomizationActivatedAt) {
    return fromInstants(key, rules, startAt, endAt, splits, doLog, randomizationActivatedAt, false);
  }

  public static Allocation fromInstants(
      final String key,
      final List<Rule> rules,
      final Instant startAt,
      final Instant endAt,
      final List<Split> splits,
      final Boolean doLog,
      final Instant randomizationActivatedAt,
      final boolean invalidRandomizationActivation) {
    return new Allocation(
        key,
        rules,
        startAt == null ? null : Date.from(startAt),
        endAt == null ? null : Date.from(endAt),
        splits,
        doLog,
        startAt,
        endAt,
        randomizationActivatedAt,
        invalidRandomizationActivation);
  }

  public Instant startAtInstant() {
    return preciseStartAt != null ? preciseStartAt : startAt == null ? null : startAt.toInstant();
  }

  public Instant endAtInstant() {
    return preciseEndAt != null ? preciseEndAt : endAt == null ? null : endAt.toInstant();
  }
}
