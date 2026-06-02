package datadog.trace.api.featureflag.ufc.v1;

import java.time.Instant;
import java.util.List;

public class Allocation {
  public final String key;
  public final List<Rule> rules;
  public final Instant startAt;
  public final Instant endAt;
  public final List<Split> splits;
  public final Boolean doLog;

  public Allocation(
      final String key,
      final List<Rule> rules,
      final Instant startAt,
      final Instant endAt,
      final List<Split> splits,
      final Boolean doLog) {
    this.key = key;
    this.rules = rules;
    this.startAt = startAt;
    this.endAt = endAt;
    this.splits = splits;
    this.doLog = doLog;
  }
}
