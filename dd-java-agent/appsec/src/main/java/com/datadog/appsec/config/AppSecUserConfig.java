package com.datadog.appsec.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AppSecUserConfig {
  public final String configKey;
  public final Map<String, Boolean> ruleToggling;
  public final Map<String, Map<String, Object>> ruleOverrides;
  public final List<Map<String, Object>> actions;
  public final List<Map<String, Object>> exclusions;
  public final List<Map<String, Object>> customRules;

  public AppSecUserConfig(
      String configKey,
      Map<String, Boolean> ruleToggling,
      Map<String, Map<String, Object>> ruleOverrides,
      List<Map<String, Object>> actions,
      List<Map<String, Object>> exclusions,
      List<Map<String, Object>> customRules) {
    this.configKey = configKey;
    this.ruleToggling = ruleToggling;
    this.ruleOverrides = ruleOverrides;
    this.actions = actions;
    this.exclusions = exclusions;
    this.customRules = customRules;
  }

  public boolean includesTogglingChanges() {
    return !this.ruleToggling.isEmpty();
  }

  public boolean includesContextChanges() {
    return !this.ruleOverrides.isEmpty()
        || !this.actions.isEmpty()
        || !this.exclusions.isEmpty()
        || !this.customRules.isEmpty();
  }

  public static class Builder {
    public final Map<String, Boolean> ruleToggling;
    public final Map<String, Map<String, Object>> ruleOverrides;
    public final List<Map<String, Object>> actions;
    public final List<Map<String, Object>> exclusions;
    public final List<Map<String, Object>> customRules;

    public Builder(Map<String, List<Map<String, Object>>> userConfig) {
      Map<String, Boolean> ruleToggling = Collections.emptyMap();
      Map<String, Map<String, Object>> ruleOverridesMap = Collections.emptyMap();
      {
        List<Map<String, Object>> rulesOverride = userConfig.get("rules_override");

        // rule toggling and on_match are handled separately downstream,
        // so it's convenient to separate them here already
        if (rulesOverride != null) {
          ruleToggling =
              rulesOverride.stream()
                  .filter(m -> m.get("id") instanceof String && m.get("enabled") instanceof Boolean)
                  .collect(
                      Collectors.toMap(
                          m -> (String) m.get("id"), m -> (Boolean) m.get("enabled"), (a, b) -> b));

          ruleOverridesMap =
              rulesOverride.stream()
                  .filter(m -> m.get("id") != null && m.get("on_match") != null)
                  .collect(
                      Collectors.toMap(
                          m -> (String) m.get("id"),
                          m -> Collections.singletonMap("on_match", m.get("on_match")),
                          (a, b) -> b));
        }
      }
      this.ruleToggling = ruleToggling;
      this.ruleOverrides = ruleOverridesMap;
      this.actions = userConfig.getOrDefault("actions", Collections.EMPTY_LIST);
      this.exclusions = userConfig.getOrDefault("exclusions", Collections.EMPTY_LIST);
      this.customRules = userConfig.getOrDefault("custom_rules", Collections.EMPTY_LIST);
    }

    // configKey is unavailable on the deserializer
    AppSecUserConfig build(String configKey) {
      return new AppSecUserConfig(
          configKey, ruleToggling, ruleOverrides, actions, exclusions, customRules);
    }
  }
}
