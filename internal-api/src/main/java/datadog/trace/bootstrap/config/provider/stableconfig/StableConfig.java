package datadog.trace.bootstrap.config.provider.stableconfig;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StableConfig {
  private final String configId;
  private final Map<String, Object> apmConfigurationDefault;
  private final List<Rule> apmConfigurationRules;

  public StableConfig(Object yaml) {
    Map<Object, Object> map = (Map<Object, Object>) yaml;
    this.configId = String.valueOf(map.get("config_id"));
    this.apmConfigurationDefault =
        unmodifiableMap(
            (Map<String, Object>) map.getOrDefault("apm_configuration_default", emptyMap()));
    this.apmConfigurationRules = parseRules(map);
  }

  // test only
  private StableConfig(String configId, Map<String, Object> apmConfigurationDefault) {
    this.configId = configId;
    this.apmConfigurationDefault = apmConfigurationDefault;
    this.apmConfigurationRules = new ArrayList<>();
  }

  public String getConfigId() {
    return configId;
  }

  public Map<String, Object> getApmConfigurationDefault() {
    return apmConfigurationDefault;
  }

  public List<Rule> getApmConfigurationRules() {
    return apmConfigurationRules;
  }

  private List<Rule> parseRules(Map<?, ?> map) {
    Object rulesObj = map.get("apm_configuration_rules");
    if (rulesObj instanceof List) {
      List<?> rulesList = (List<?>) rulesObj;
      List<Rule> rules = new ArrayList<>();
      for (Object ruleObj : rulesList) {
        if (ruleObj instanceof Map) {
          rules.add(Rule.from((Map<?, ?>) ruleObj));
        } else {
          StableConfigMappingException.throwStableConfigMappingException(
              "Rule must be a map, but got: " + ruleObj.getClass().getSimpleName(), ruleObj);
          return emptyList();
        }
      }
      return unmodifiableList(rules);
    }
    return emptyList();
  }
}
