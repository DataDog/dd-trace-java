package datadog.trace.bootstrap.config.provider.stableconfig;

import static datadog.trace.bootstrap.config.provider.stableconfig.StableConfigMappingException.throwStableConfigMappingException;
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
    this.configId = map.get("config_id") == null ? null : String.valueOf(map.get("config_id"));

    // getOrDefault returns null if key exists with null value, so we need explicit null check
    Map<String, Object> apmConfigDefault =
        (Map<String, Object>) map.get("apm_configuration_default");
    this.apmConfigurationDefault =
        unmodifiableMap(apmConfigDefault != null ? apmConfigDefault : emptyMap());

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
          throwStableConfigMappingException(
              "Rule must be a map, but got: " + ruleObj.getClass().getSimpleName() + ": ", ruleObj);
          return emptyList();
        }
      }
      return unmodifiableList(rules);
    }
    return emptyList();
  }
}
