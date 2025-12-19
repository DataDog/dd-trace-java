package com.datadog.appsec.report;

import static com.datadog.appsec.ddwaf.WAFResultData.Rule;
import static com.datadog.appsec.ddwaf.WAFResultData.RuleMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AppSecEvent {

  @com.squareup.moshi.Json(name = "rule")
  private Rule rule;

  @com.squareup.moshi.Json(name = "rule_matches")
  private List<RuleMatch> ruleMatches = new ArrayList<>();

  @com.squareup.moshi.Json(name = "span_id")
  private Long spanId;

  @com.squareup.moshi.Json(name = "stack_id")
  private String stackId;

  @com.squareup.moshi.Json(name = "security_response_id")
  private String securityResponseId;

  public Rule getRule() {
    return rule;
  }

  public List<RuleMatch> getRuleMatches() {
    return ruleMatches;
  }

  public Long getSpanId() {
    return spanId;
  }

  public String getStackId() {
    return stackId;
  }

  public String getSecurityResponseId() {
    return securityResponseId;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(AppSecEvent.class.getName())
        .append('@')
        .append(Integer.toHexString(System.identityHashCode(this)))
        .append('[');
    sb.append("rule");
    sb.append('=');
    sb.append(((this.rule == null) ? "<null>" : this.rule));
    sb.append(',');
    sb.append("ruleMatches");
    sb.append('=');
    sb.append(((this.ruleMatches == null) ? "<null>" : this.ruleMatches));
    sb.append(',');
    sb.append("spanId");
    sb.append('=');
    sb.append(((this.spanId == null) ? "<null>" : this.spanId));
    sb.append("stackId");
    sb.append('=');
    sb.append(((this.stackId == null) ? "<null>" : this.stackId));
    sb.append(',');
    sb.append("securityResponseId");
    sb.append('=');
    sb.append(((this.securityResponseId == null) ? "<null>" : this.securityResponseId));
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
    result = ((result * 31) + ((this.rule == null) ? 0 : this.rule.hashCode()));
    result = ((result * 31) + ((this.ruleMatches == null) ? 0 : this.ruleMatches.hashCode()));
    result = ((result * 31) + ((this.spanId == null) ? 0 : this.spanId.hashCode()));
    result = ((result * 31) + ((this.stackId == null) ? 0 : this.stackId.hashCode()));
    result =
        ((result * 31)
            + ((this.securityResponseId == null) ? 0 : this.securityResponseId.hashCode()));
    return result;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof AppSecEvent)) {
      return false;
    }
    AppSecEvent rhs = ((AppSecEvent) other);
    return ((Objects.equals(this.rule, rhs.rule))
        && (Objects.equals(this.ruleMatches, rhs.ruleMatches))
        && (Objects.equals(this.spanId, rhs.spanId))
        && (Objects.equals(this.stackId, rhs.stackId))
        && (Objects.equals(this.securityResponseId, rhs.securityResponseId)));
  }

  public static class Builder {

    protected AppSecEvent instance;

    public Builder() {
      this.instance = new AppSecEvent();
    }

    public AppSecEvent build() {
      AppSecEvent result;
      result = this.instance;
      this.instance = null;
      return result;
    }

    public Builder withRule(Rule rule) {
      this.instance.rule = rule;
      return this;
    }

    public Builder withRuleMatches(List<RuleMatch> ruleMatches) {
      this.instance.ruleMatches = ruleMatches;
      return this;
    }

    public Builder withSpanId(Long spanId) {
      this.instance.spanId = spanId;
      return this;
    }

    public Builder withStackId(String stackId) {
      this.instance.stackId = stackId;
      return this;
    }

    public Builder withSecurityResponseId(String securityResponseId) {
      this.instance.securityResponseId = securityResponseId;
      return this;
    }
  }
}
