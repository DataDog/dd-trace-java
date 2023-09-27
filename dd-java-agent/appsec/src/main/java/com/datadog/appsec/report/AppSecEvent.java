package com.datadog.appsec.report;

import java.util.ArrayList;
import java.util.List;

public class AppSecEvent {

  @com.squareup.moshi.Json(name = "rule")
  private Rule rule;

  @com.squareup.moshi.Json(name = "rule_matches")
  private List<RuleMatch> ruleMatches = new ArrayList<>();

  public Rule getRule() {
    return rule;
  }

  public void setRule(Rule rule) {
    this.rule = rule;
  }

  public List<RuleMatch> getRuleMatches() {
    return ruleMatches;
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
    return (((this.rule == rhs.rule) || ((this.rule != null) && this.rule.equals(rhs.rule)))
        && ((this.ruleMatches == rhs.ruleMatches)
            || ((this.ruleMatches != null) && this.ruleMatches.equals(rhs.ruleMatches))));
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
  }
}
