package com.datadog.appsec.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CurrentAppSecConfig {
  AppSecConfig ddConfig; // assume there's only one of these
  CollectedUserConfigs userConfigs = new CollectedUserConfigs();
  MergedAsmData mergedAsmData = new MergedAsmData(new HashMap<>());

  public boolean dirtyWafRules;
  public boolean dirtyToggling;
  public boolean dirtyWafData;

  public AppSecConfig getMergedAppSecConfig() {
    AppSecConfig config = ddConfig;
    List<AppSecUserConfig> userConfigs = this.userConfigs;
    for (AppSecUserConfig userCfg : userConfigs) {
      config = config.mergeAppSecUserConfig(userCfg);
    }

    return config;
  }

  public Map<String, Boolean> getMergedRuleToggling() {
    return userConfigs.stream()
        .flatMap(userCfg -> userCfg.ruleToggling.entrySet().stream())
        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue(), (a, b) -> b));
  }

  public MergedAsmData getMergedAsmData() {
    return this.mergedAsmData;
  }

  public void clearDirty() {
    this.dirtyToggling = this.dirtyWafData = this.dirtyWafRules = false;
  }

  public boolean isAnyDirty() {
    return this.dirtyWafRules || this.dirtyWafData || this.dirtyToggling;
  }
}
