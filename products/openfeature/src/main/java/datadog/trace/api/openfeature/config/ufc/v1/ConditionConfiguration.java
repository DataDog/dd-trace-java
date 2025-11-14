package datadog.trace.api.openfeature.config.ufc.v1;

public class ConditionConfiguration {
  public final ConditionOperator operator;
  public final String attribute;
  public final Object value;

  public ConditionConfiguration(
      final ConditionOperator operator, final String attribute, final Object value) {
    this.operator = operator;
    this.attribute = attribute;
    this.value = value;
  }
}
