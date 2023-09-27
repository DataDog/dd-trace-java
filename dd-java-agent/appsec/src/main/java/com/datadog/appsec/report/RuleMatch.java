package com.datadog.appsec.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RuleMatch {
  /**
   * The rule operator that triggered this event. For example, ``match_regex`` or ``phrase_match``.
   * (Required)
   */
  @com.squareup.moshi.Json(name = "operator")
  private String operator;
  /**
   * The rule operator operand that triggered this event. For example, the word that triggered using
   * the ``phrase_match`` operator. (Required)
   */
  @com.squareup.moshi.Json(name = "operator_value")
  private String operatorValue;
  /** (Required) */
  @com.squareup.moshi.Json(name = "parameters")
  private List<Parameter> parameters = new ArrayList<>();

  /**
   * The rule operator that triggered this event. For example, ``match_regex`` or ``phrase_match``.
   * (Required)
   */
  public String getOperator() {
    return operator;
  }

  /**
   * The rule operator that triggered this event. For example, ``match_regex`` or ``phrase_match``.
   * (Required)
   */
  public void setOperator(String operator) {
    this.operator = operator;
  }

  /**
   * The rule operator operand that triggered this event. For example, the word that triggered using
   * the ``phrase_match`` operator. (Required)
   */
  public String getOperatorValue() {
    return operatorValue;
  }

  /** (Required) */
  public List<Parameter> getParameters() {
    return parameters;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(RuleMatch.class.getName())
        .append('@')
        .append(Integer.toHexString(System.identityHashCode(this)))
        .append('[');
    sb.append("operator");
    sb.append('=');
    sb.append(((this.operator == null) ? "<null>" : this.operator));
    sb.append(',');
    sb.append("operatorValue");
    sb.append('=');
    sb.append(((this.operatorValue == null) ? "<null>" : this.operatorValue));
    sb.append(',');
    sb.append("parameters");
    sb.append('=');
    sb.append(((this.parameters == null) ? "<null>" : this.parameters));
    sb.append(',');
    if (sb.charAt((sb.length() - 1)) == ',') {
      sb.setCharAt((sb.length() - 1), ']');
    } else {
      sb.append(']');
    }
    return sb.toString();
  }

  @Override
  public int hashCode() {
    int result = 1;
    result = ((result * 31) + ((this.parameters == null) ? 0 : this.parameters.hashCode()));
    result = ((result * 31) + ((this.operator == null) ? 0 : this.operator.hashCode()));
    result = ((result * 31) + ((this.operatorValue == null) ? 0 : this.operatorValue.hashCode()));
    return result;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof RuleMatch)) {
      return false;
    }
    RuleMatch rhs = ((RuleMatch) other);
    return (Objects.equals(this.parameters, rhs.parameters))
        && Objects.equals(this.operator, rhs.operator)
        && Objects.equals(this.operatorValue, rhs.operatorValue);
  }

  public static class Builder {

    protected RuleMatch instance;

    public Builder() {
      this.instance = new RuleMatch();
    }

    public RuleMatch build() {
      RuleMatch result;
      result = this.instance;
      this.instance = null;
      return result;
    }

    public Builder withOperator(String operator) {
      this.instance.operator = operator;
      return this;
    }

    public Builder withOperatorValue(String operatorValue) {
      this.instance.operatorValue = operatorValue;
      return this;
    }

    public Builder withParameters(List<Parameter> parameters) {
      this.instance.parameters = parameters;
      return this;
    }
  }
}
