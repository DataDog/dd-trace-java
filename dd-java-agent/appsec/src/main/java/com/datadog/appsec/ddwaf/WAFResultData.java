package com.datadog.appsec.ddwaf;

import java.util.List;
import java.util.Map;

/**
 * Deserialization target for the JSON the WAF returns alongside a match.
 *
 * <p>These types are bound by a Moshi <em>reflective</em> adapter built in {@link WAFModule}'s
 * static initializer. Moshi instantiates them through a no-arg constructor, falling back to {@code
 * sun.misc.Unsafe} when none is declared; on a runtime that does not resolve the {@code
 * jdk.unsupported} module that fallback is unavailable and adapter construction fails, taking down
 * AppSec startup with it. The {@code Unsafe} fallback is therefore not something to rely on: every
 * class here must keep a no-arg constructor, including the implicit one — adding an all-args
 * constructor without also declaring a no-arg one removes it.
 */
public class WAFResultData {
  Rule rule;
  List<RuleMatch> rule_matches;
  String stack_id;

  public static class RuleMatch {
    String operator;
    String operator_value;
    List<Parameter> parameters;

    RuleMatch() {}

    public RuleMatch(String operator, String operator_value, List<Parameter> parameters) {
      this.operator = operator;
      this.operator_value = operator_value;
      this.parameters = parameters;
    }
  }

  public static class Rule {
    public String id; // expose for log message
    String name;
    Map<String, String> tags;

    Rule() {}

    public Rule(String id, String name, Map<String, String> tags) {
      this.id = id;
      this.name = name;
      this.tags = tags;
    }
  }

  public static class Parameter extends MatchInfo {
    MatchInfo resource;
    MatchInfo params;
    MatchInfo db_type;
    List<String> highlight;

    Parameter() {}

    public Parameter(String address, List<Object> key_path, String value, List<String> highlight) {
      super(address, key_path, value);
      this.highlight = highlight;
    }
  }

  public static class MatchInfo {
    String address;
    List<Object> key_path;
    String value;

    MatchInfo() {}

    public MatchInfo(String address, List<Object> key_path, String value) {
      this.address = address;
      this.key_path = key_path;
      this.value = value;
    }
  }
}
