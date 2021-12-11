package com.datadog.appsec.powerwaf;

import java.util.List;
import java.util.Map;

public final class PowerWAFResultData {
  Rule rule;
  List<RuleMatch> rule_matches;

  public static final class RuleMatch {
    String operator;
    String operator_value;
    List<Parameter> parameters;
  }

  public static final class Rule {
    String id;
    String name;
    Map<String, String> tags;
  }

  public static final class Parameter {
    String address;
    List<Object> key_path;
    String value;
    List<String> highlight;
  }
}
