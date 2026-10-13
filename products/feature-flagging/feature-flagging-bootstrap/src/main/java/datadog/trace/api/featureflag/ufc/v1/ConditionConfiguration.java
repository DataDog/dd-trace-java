package datadog.trace.api.featureflag.ufc.v1;

public class ConditionConfiguration {
  public final ConditionOperator operator;
  public final String attribute;
  public final Object value;

  // The validated, parsed SemVer condition value. Set during configuration preprocessing
  // (not from JSON) when the operator is a SEMVER_* operator.
  public transient ParsedSemver semverComparand;

  public ConditionConfiguration(
      final ConditionOperator operator, final String attribute, final Object value) {
    this.operator = operator;
    this.attribute = attribute;
    this.value = value;
  }
}
