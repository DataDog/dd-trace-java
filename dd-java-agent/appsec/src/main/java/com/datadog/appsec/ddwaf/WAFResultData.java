package com.datadog.appsec.ddwaf;

import java.util.List;
import java.util.Map;

public class WAFResultData {
  Rule rule;
  List<RuleMatch> rule_matches;
  String stack_id;

  public static class RuleMatch {
    String operator;
    String operator_value;
    List<Parameter> parameters;

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

    public Parameter(String address, List<Object> key_path, String value, List<String> highlight) {
      super(address, key_path, value);
      this.highlight = highlight;
    }
  }

  public static class MatchInfo {
    String address;
    List<Object> key_path;
    String value;

    public MatchInfo(String address, List<Object> key_path, String value) {
      this.address = address;
      this.key_path = key_path;
      this.value = value;
    }
  }
}
