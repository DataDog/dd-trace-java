package com.datadog.appsec.powerwaf;

import java.util.List;
import java.util.Map;

public class PowerWAFResultData {
  Rule rule;
  List<RuleMatch> rule_matches;

  public static class RuleMatch {
    String operator;
    String operator_value;
    List<Parameter> parameters;
  }

  public static class Rule {
    String id;
    String name;
    Map<String, String> tags;
  }

  public static class Parameter {
    String address;
    List<Object> key_path;
    String value;
    List<String> highlight;
  }
}
