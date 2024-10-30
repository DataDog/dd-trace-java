package com.datadog.appsec.config;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CurrentAppSecConfig {
  private static final Logger log = LoggerFactory.getLogger(CurrentAppSecConfig.class);

  private AppSecConfig ddConfig; // assume there's only one of these
  CollectedUserConfigs userConfigs = new CollectedUserConfigs();
  MergedAsmData mergedAsmData = new MergedAsmData(new HashMap<>());
  public final DirtyStatus dirtyStatus = new DirtyStatus();

  @SuppressWarnings("unchecked")
  public void setDdConfig(AppSecConfig newConfig) {
    this.ddConfig = newConfig;

    final Map<String, Object> rawConfig = newConfig.getRawConfig();
    List<Map<String, Object>> rules = (List<Map<String, Object>>) rawConfig.get("rules_data");
    List<Map<String, Object>> exclusions =
        (List<Map<String, Object>>) rawConfig.get("exclusion_data");
    if (rules != null || exclusions != null) {
      final AppSecData data = new AppSecData();
      data.setRules(rules);
      data.setExclusion(exclusions);
      mergedAsmData.addConfig(MergedAsmData.KEY_BUNDLED_DATA, data);
    } else {
      mergedAsmData.removeConfig(MergedAsmData.KEY_BUNDLED_DATA);
    }
  }

  public static class DirtyStatus {
    public boolean rules;
    public boolean customRules;
    public boolean ruleOverrides;
    public boolean actions;
    public boolean data;
    public boolean exclusions;

    public void mergeFrom(DirtyStatus o) {
      rules = rules || o.rules;
      customRules = customRules || o.customRules;
      ruleOverrides = ruleOverrides || o.ruleOverrides;
      actions = actions || o.actions;
      data = data || o.data;
      exclusions = exclusions || o.exclusions;
    }

    public void clearDirty() {
      rules = customRules = ruleOverrides = actions = data = exclusions = false;
    }

    public void markAllDirty() {
      rules = customRules = ruleOverrides = actions = data = exclusions = true;
    }

    public boolean isAnyDirty() {
      return isDirtyForActions() || isDirtyForDdwafUpdate();
    }

    public boolean isDirtyForDdwafUpdate() {
      return rules || customRules || ruleOverrides || data || exclusions;
    }

    public boolean isDirtyForActions() {
      return actions;
    }
  }

  public AppSecConfig getMergedUpdateConfig() throws IOException {
    if (!dirtyStatus.isAnyDirty()) {
      throw new IllegalStateException(
          "Can't call getMergedUpdateConfig without any dirty property");
    }

    Map<String, Object> mso = new HashMap<>();
    if (dirtyStatus.rules) {
      mso.put("rules", ddConfig.getRawConfig().getOrDefault("rules", Collections.emptyList()));
      mso.put(
          "processors",
          ddConfig.getRawConfig().getOrDefault("processors", Collections.emptyList()));
      mso.put(
          "scanners", ddConfig.getRawConfig().getOrDefault("scanners", Collections.emptyList()));
    }
    if (dirtyStatus.customRules) {
      mso.put("custom_rules", getMergedCustomRules());
    }
    if (dirtyStatus.exclusions) {
      mso.put("exclusions", getMergedExclusions());
    }
    if (dirtyStatus.ruleOverrides) {
      mso.put("rules_override", getMergedRuleOverrides());
    }
    if (dirtyStatus.data) {
      final AppSecData data = mergedAsmData.getMergedData();
      mso.put("rules_data", data.getRules());
      mso.put("exclusion_data", data.getExclusion());
    }
    if (dirtyStatus.actions) {
      mso.put("actions", getMergedActions());
    }

    mso.put("version", ddConfig.getVersion() == null ? "2.1" : ddConfig.getVersion());

    if (dirtyStatus.isAnyDirty()) {
      mso.put("metadata", ddConfig.getRawConfig().getOrDefault("metadata", Collections.emptyMap()));
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "Providing WAF config with: "
              + "rules: {}, custom_rules: {}, exclusions: {}, ruleOverrides: {}, rules_data: {}, exclusion_data: {}, actions: {}",
          debugRuleSummary(mso),
          debugCustomRuleSummary(mso),
          debugExclusionsSummary(mso),
          debugRuleOverridesSummary(mso),
          debugRulesDataSummary(mso),
          debugExclusionDataSummary(mso),
          debugActionsSummary(mso));
    }
    return AppSecConfig.valueOf(mso);
  }

  private static String debugActionsSummary(Map<String, Object> mso) {
    List<Map<String, Object>> actions = (List<Map<String, Object>>) mso.get("actions");
    if (actions == null) {
      return "<absent>";
    }
    return "["
        + actions.size()
        + " actions with ids "
        + actions.stream().map(rd -> String.valueOf(rd.get("id"))).collect(Collectors.joining(", "))
        + "]";
  }

  private static String debugRulesDataSummary(Map<String, Object> mso) {
    List<Map<String, Object>> rulesData = (List<Map<String, Object>>) mso.get("rules_data");
    if (rulesData == null) {
      return "<absent>";
    }
    return "["
        + rulesData.size()
        + " rules data sets with ids "
        + rulesData.stream()
            .map(rd -> String.valueOf(rd.get("id")))
            .collect(Collectors.joining(", "))
        + "]";
  }

  private static String debugExclusionDataSummary(Map<String, Object> mso) {
    List<Map<String, Object>> exclusionData = (List<Map<String, Object>>) mso.get("exclusion_data");
    if (exclusionData == null) {
      return "<absent>";
    }
    return "["
        + exclusionData.size()
        + " exclusion data sets with ids "
        + exclusionData.stream()
            .map(rd -> String.valueOf(rd.get("id")))
            .collect(Collectors.joining(", "))
        + "]";
  }

  private static String debugRuleOverridesSummary(Map<String, Object> mso) {
    List<Map<String, Object>> overrides = (List<Map<String, Object>>) mso.get("rules_override");
    if (overrides == null) {
      return "<absent>";
    }
    return "[" + overrides.size() + " rule overrides]";
  }

  private static String debugExclusionsSummary(Map<String, Object> mso) {
    List<Map<String, Object>> exclusions = (List<Map<String, Object>>) mso.get("exclusions");
    if (exclusions == null) {
      return "<absent>";
    }
    return "["
        + exclusions.size()
        + " exclusions with ids "
        + exclusions.stream()
            .map(ex -> String.valueOf(ex.get("id")))
            .collect(Collectors.joining(", "))
        + "]";
  }

  private static String debugRuleSummary(Map<String, Object> mso) {
    List<Map<String, Object>> rules = (List<Map<String, Object>>) mso.get("rules");
    if (rules == null) {
      return "<absent>";
    }
    return "[" + rules.size() + " rules]";
  }

  private static String debugCustomRuleSummary(Map<String, Object> mso) {
    List<Map<String, Object>> rules = (List<Map<String, Object>>) mso.get("custom_rules");
    if (rules == null) {
      return "<absent>";
    }
    return "[" + rules.size() + " rules]";
  }

  // does not include default actions
  private List<Map<String, Object>> getMergedActions() {
    List<Map<String, Object>> actions =
        (List<Map<String, Object>>)
            ddConfig.getRawConfig().getOrDefault("actions", Collections.EMPTY_LIST);
    return userConfigs.stream()
        .filter(userCfg -> !userCfg.actions.isEmpty())
        .reduce(actions, (a, b) -> b.actions, CurrentAppSecConfig::mergeMapsByIdKeepLatest);
  }

  private List<Map<String, Object>> getMergedCustomRules() {
    return this.userConfigs.stream()
        .map(uc -> uc.customRules)
        .reduce(Collections.emptyList(), CurrentAppSecConfig::mergeMapsByIdKeepLatest);
  }

  private List<Map<String, Object>> getMergedExclusions() {
    List<Map<String, Object>> exclusions =
        (List<Map<String, Object>>)
            ddConfig.getRawConfig().getOrDefault("exclusions", Collections.emptyList());

    List<AppSecUserConfig> userConfigs = this.userConfigs;
    for (AppSecUserConfig userCfg : userConfigs) {
      if (!userCfg.exclusions.isEmpty()) {
        exclusions = mergeMapsByIdKeepLatest(exclusions, userCfg.exclusions);
      }
    }

    return exclusions;
  }

  private List<Map<String, Object>> getMergedRuleOverrides() {
    List<Map<String, Object>> ruleOverrides =
        (List<Map<String, Object>>)
            ddConfig.getRawConfig().getOrDefault("rules_override", Collections.emptyList());

    List<AppSecUserConfig> userConfigs = this.userConfigs;
    for (AppSecUserConfig userCfg : userConfigs) {
      if (!userCfg.ruleOverrides.isEmpty()) {
        // plain merge; overrides have no ids
        ruleOverrides =
            Stream.concat(ruleOverrides.stream(), userCfg.ruleOverrides.stream())
                .collect(Collectors.toList());
      }
    }

    return ruleOverrides;
  }

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
}
