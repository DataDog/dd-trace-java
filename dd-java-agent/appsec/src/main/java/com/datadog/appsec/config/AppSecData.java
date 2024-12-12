package com.datadog.appsec.config;

import com.squareup.moshi.Json;
import java.util.List;
import java.util.Map;

public class AppSecData {

  @Json(name = "rules_data")
  private List<Map<String, Object>> rules;

  @Json(name = "exclusion_data")
  private List<Map<String, Object>> exclusion;

  public List<Map<String, Object>> getRules() {
    return rules;
  }

  public void setRules(List<Map<String, Object>> rules) {
    this.rules = rules;
  }

  public List<Map<String, Object>> getExclusion() {
    return exclusion;
  }

  public void setExclusion(List<Map<String, Object>> exclusion) {
    this.exclusion = exclusion;
  }
}
