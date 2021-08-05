package com.datadog.appsec.powerwaf;

import java.util.List;

public class PowerWAFResultData {
  int ret_code;
  String flow;
  String step;
  String rule;
  List<Filter> filter;

  public static class Filter {
    String operator;
    String operator_value;
    String binding_accessor;
    String manifest_key;
    String resolved_value;
    String match_status;
  }
}
