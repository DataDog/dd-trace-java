package com.datadog.appsec.powerwaf;

import java.util.List;
import java.util.Map;

public class PowerWAFResultData {
  Rule rule;
  List<RuleMatch> rule_matches;
  String stack_id;

  public static class RuleMatch {
    String operator;
    String operator_value;
    List<Parameter> parameters;
  }

  public static class Rule {
    public String id; // expose for log message
    String name;
    Map<String, String> tags;
  }

  public static class Parameter extends MatchInfo {
    MatchInfo resource;
    MatchInfo params;
    MatchInfo db_type;
    List<String> highlight;
  }

  public static class MatchInfo {
    String address;
    List<Object> key_path;
    String value;
  }
}
