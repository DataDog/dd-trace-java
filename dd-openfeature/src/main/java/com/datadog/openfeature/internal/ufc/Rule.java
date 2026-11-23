package com.datadog.openfeature.internal.ufc;

import java.util.List;

public final class Rule {
  public final List<ConditionConfiguration> conditions;

  public Rule(final List<ConditionConfiguration> conditions) {
    this.conditions = conditions;
  }
}
