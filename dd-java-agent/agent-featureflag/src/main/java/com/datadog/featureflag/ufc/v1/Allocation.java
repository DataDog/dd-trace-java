package com.datadog.featureflag.ufc.v1;

import java.util.List;

public class Allocation {
  public final String key;
  public final List<Rule> rules;
  public final String startAt;
  public final String endAt;
  public final List<Split> splits;
  public final Boolean doLog;

  public Allocation(
      String key,
      List<Rule> rules,
      String startAt,
      String endAt,
      List<Split> splits,
      Boolean doLog) {
    this.key = key;
    this.rules = rules;
    this.startAt = startAt;
    this.endAt = endAt;
    this.splits = splits;
    this.doLog = doLog;
  }
}
