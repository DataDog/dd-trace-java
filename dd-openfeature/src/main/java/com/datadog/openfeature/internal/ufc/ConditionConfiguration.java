package com.datadog.openfeature.internal.ufc;

public final class ConditionConfiguration {
  public final ConditionOperator operator;
  public final String attribute;
  public final String flagEvaluationKey;
  public final Object value;

  // The validated, parsed SemVer condition value. Set during configuration preprocessing
  // (not from JSON) when the operator is a SEMVER_* operator.
  public transient ParsedSemver semverComparand;

  public ConditionConfiguration(
      final ConditionOperator operator, final String attribute, final Object value) {
    this(operator, attribute, null, value);
  }

  public ConditionConfiguration(
      final ConditionOperator operator,
      final String attribute,
      final String flagEvaluationKey,
      final Object value) {
    this.operator = operator;
    this.attribute = attribute;
    this.flagEvaluationKey = flagEvaluationKey;
    this.value = value;
  }
}
