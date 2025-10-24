package com.datadog.featureflag.ufc.v1;

public class ConditionConfiguration {
  public final ConditionOperator operator;
  public final String attribute;
  public final Object value;

  public ConditionConfiguration(ConditionOperator operator, String attribute, Object value) {
    this.operator = operator;
    this.attribute = attribute;
    this.value = value;
  }
}
