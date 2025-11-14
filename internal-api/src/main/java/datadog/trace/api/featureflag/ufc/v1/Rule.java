package datadog.trace.api.featureflag.ufc.v1;

import java.util.List;

public class Rule {
  public final List<ConditionConfiguration> conditions;

  public Rule(final List<ConditionConfiguration> conditions) {
    this.conditions = conditions;
  }
}
