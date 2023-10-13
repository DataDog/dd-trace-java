package com.datadog.appsec.config;

import com.datadog.appsec.config.CurrentAppSecConfig.DirtyStatus;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AppSecUserConfig {
  public final String configKey;
  public final List<Map<String, Object>> ruleOverrides;
  public final List<Map<String, Object>> actions;
  public final List<Map<String, Object>> exclusions;
  public final List<Map<String, Object>> customRules;

  public AppSecUserConfig(
      String configKey,
      List<Map<String, Object>> ruleOverrides,
      List<Map<String, Object>> actions,
      List<Map<String, Object>> exclusions,
      List<Map<String, Object>> customRules) {
    this.configKey = configKey;
    this.ruleOverrides = ruleOverrides;
    this.actions = actions;
    this.exclusions = exclusions;
    this.customRules = customRules;
  }

  public DirtyStatus dirtyEffect() {
    DirtyStatus ds = new DirtyStatus();
    if (!this.ruleOverrides.isEmpty()) {
      ds.ruleOverrides = true;
    }
    if (!this.actions.isEmpty()) {
      ds.actions = true;
    }
    if (!this.exclusions.isEmpty()) {
      ds.exclusions = true;
    }
    if (!this.customRules.isEmpty()) {
      ds.customRules = true;
    }
    // data not included here
    return ds;
  }

  public static class Builder {
    public final List<Map<String, Object>> ruleOverrides;
    public final List<Map<String, Object>> actions;
    public final List<Map<String, Object>> exclusions;
    public final List<Map<String, Object>> customRules;

    public Builder(Map<String, List<Map<String, Object>>> userConfig) {
      this.ruleOverrides = userConfig.getOrDefault("rules_override", Collections.EMPTY_LIST);
      this.actions = userConfig.getOrDefault("actions", Collections.EMPTY_LIST);
      this.exclusions = userConfig.getOrDefault("exclusions", Collections.EMPTY_LIST);
      this.customRules = userConfig.getOrDefault("custom_rules", Collections.EMPTY_LIST);
    }

    // configKey is unavailable on the deserializer
    AppSecUserConfig build(String configKey) {
      return new AppSecUserConfig(configKey, ruleOverrides, actions, exclusions, customRules);
    }
  }
}
