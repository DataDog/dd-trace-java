package com.datadog.appsec.config;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toMap;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface AppSecConfig {

  Moshi MOSHI = new Moshi.Builder().build();
  JsonAdapter<AppSecConfigV1> ADAPTER_V1 = MOSHI.adapter(AppSecConfigV1.class);
  JsonAdapter<AppSecConfigV2> ADAPTER_V2 = MOSHI.adapter(AppSecConfigV2.class);
  JsonAdapter<List<Rule>> RULE_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(List.class, Rule.class));

  String getVersion();

  List<Rule> getRules();

  Map<String, Object> getRawConfig();

  default AppSecConfig mergeAppSecUserConfig(AppSecUserConfig cfg) {
    if (!(this instanceof AppSecConfigV2)) {
      throw new RuntimeException("Only a V2 configuration can have overrides");
    }
    if (!cfg.includesContextChanges()) {
      return this;
    }
    AppSecConfigV2 thiz = (AppSecConfigV2) this;
    AppSecConfigV2 result = Helper.copyExceptRules(thiz);

    {
      List<Map<String, Object>> origRawRules =
          (List<Map<String, Object>>) getRawConfig().getOrDefault("rules", Collections.EMPTY_LIST);
      List<Map<String, Object>> newRawRules = origRawRules;
      List<Rule> newRules = thiz.rules;
      if (!cfg.customRules.isEmpty()) {
        newRawRules = Helper.mergeMapsByIdKeepLatest(newRawRules, cfg.customRules);
      }
      if (!cfg.ruleOverrides.isEmpty()) {
        newRawRules = Helper.mergeRuleOverrides(newRawRules, cfg.ruleOverrides);
      }
      if (newRawRules != origRawRules) {
        newRules = RULE_ADAPTER.fromJsonValue(newRawRules);
      }
      result.getRawConfig().put("rules", newRawRules);
      result.rules = newRules;
    }

    if (!cfg.exclusions.isEmpty()) {
      result
          .getRawConfig()
          .put(
              "exclusions",
              Helper.mergeMapsByIdKeepLatest(
                  (List<Map<String, Object>>) result.getRawConfig().get("exclusions"),
                  cfg.exclusions));
    }

    if (!cfg.actions.isEmpty()) {
      result
          .getRawConfig()
          .put(
              "actions",
              Helper.mergeMapsByIdKeepLatest(
                  (List<Map<String, Object>>) result.getRawConfig().get("actions"), cfg.actions));
    }

    return result;
  }

  class Helper {
    private static List<Map<String, Object>> mergeMapsByIdKeepLatest(
        List<Map<String, Object>> l1, List<Map<String, Object>> l2) {
      return Stream.concat(l1.stream(), l2.stream())
          .collect(
              collectingAndThen(
                  toMap(
                      m -> String.valueOf(m.get("id")),
                      identity(),
                      (m1, m2) -> m2,
                      LinkedHashMap::new),
                  mapOfMaps -> new ArrayList<>(mapOfMaps.values())));
    }

    private static List<Map<String, Object>> mergeRuleOverrides(
        List<Map<String, Object>> rules, Map<String /* id */, Map<String, Object>> overridesById) {
      return rules.stream()
          .map(
              rule -> {
                Map<String, Object> overrideSpec = overridesById.get(rule.get("id"));
                if (overrideSpec == null) {
                  return rule;
                }
                HashMap<String, Object> newRule = new HashMap<>(rule);
                newRule.putAll(overrideSpec);
                return newRule;
              })
          .collect(Collectors.toList());
    }

    private static AppSecConfigV2 copyExceptRules(AppSecConfigV2 original) {
      AppSecConfigV2 result = new AppSecConfigV2();
      result.rawConfig = new HashMap<>();
      result.version = original.version;
      result.rawConfig.put("version", original.rawConfig.get("version"));
      result.rawConfig.put(
          "metadata", original.rawConfig.getOrDefault("metadata", Collections.EMPTY_MAP));
      result.rawConfig.put(
          "exclusions", original.rawConfig.getOrDefault("exclusions", Collections.EMPTY_LIST));
      result.rawConfig.put(
          "actions", original.rawConfig.getOrDefault("actions", Collections.EMPTY_LIST));
      result.rawConfig.put(
          "rules_data", original.rawConfig.getOrDefault("rules_data", Collections.EMPTY_LIST));
      return result;
    }
  }

  static AppSecConfig valueOf(Map<String, Object> rawConfig) throws IOException {
    if (rawConfig == null) {
      return null;
    }

    String version = String.valueOf(rawConfig.get("version"));
    if (version == null) {
      throw new IOException("Unable deserialize raw json config");
    }

    // For version 1.x
    if (version.startsWith("1.")) {
      AppSecConfigV1 config = ADAPTER_V1.fromJsonValue(rawConfig);
      config.rawConfig = rawConfig;
      return config;
    }

    // For version 2.x
    if (version.startsWith("2.")) {
      AppSecConfigV2 config = ADAPTER_V2.fromJsonValue(rawConfig);
      config.rawConfig = rawConfig;
      return config;
    }

    throw new IOException("Config version '" + version + "' is not supported");
  }

  class Rule {
    private String id;
    private String name;
    private Map<String, String> tags;
    private Object conditions;
    private Object transformers;

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public Map<String, String> getTags() {
      return tags;
    }
  }

  class AppSecConfigV1 implements AppSecConfig {

    private String version;
    private List<Rule> events;
    private Map<String, Object> rawConfig;

    @Override
    public String getVersion() {
      return null;
    }

    @Override
    public List<Rule> getRules() {
      return events != null ? events : Collections.emptyList();
    }

    @Override
    public Map<String, Object> getRawConfig() {
      return rawConfig;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AppSecConfigV1 that = (AppSecConfigV1) o;
      return Objects.equals(version, that.version)
          && Objects.equals(events, that.events)
          && Objects.equals(rawConfig, that.rawConfig);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + (version == null ? 0 : version.hashCode());
      hash = 31 * hash + (events == null ? 0 : events.hashCode());
      hash = 31 * hash + (rawConfig == null ? 0 : rawConfig.hashCode());
      return hash;
    }
  }

  class AppSecConfigV2 implements AppSecConfig {

    private String version;
    private List<Rule> rules;
    // Note: the tendency is for new code to manipulate rawConfig directly
    private Map<String, Object> rawConfig;

    @Override
    public String getVersion() {
      return version;
    }

    @Override
    public List<Rule> getRules() {
      return rules != null ? rules : Collections.emptyList();
    }

    @Override
    public Map<String, Object> getRawConfig() {
      return rawConfig;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AppSecConfigV2 that = (AppSecConfigV2) o;
      return Objects.equals(version, that.version)
          && Objects.equals(rules, that.rules)
          && Objects.equals(rawConfig, that.rawConfig);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + (version == null ? 0 : version.hashCode());
      hash = 31 * hash + (rules == null ? 0 : rules.hashCode());
      hash = 31 * hash + (rawConfig == null ? 0 : rawConfig.hashCode());
      return hash;
    }
  }
}
