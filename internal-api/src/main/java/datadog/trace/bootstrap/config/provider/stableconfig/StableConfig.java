package datadog.trace.bootstrap.config.provider.stableconfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class StableConfig {
  private final String configId;
  private final Map<String, Object> apmConfigurationDefault;
  private final List<Rule> apmConfigurationRules;

  public StableConfig(Object yaml) {
    Map<Object, Object> map = (Map<Object, Object>) yaml;
    this.configId = String.valueOf(map.get("config_id"));
    this.apmConfigurationDefault =
        Collections.unmodifiableMap(
            (Map<String, Object>)
                map.getOrDefault("apm_configuration_default", new LinkedHashMap<>()));
    this.apmConfigurationRules =
        Collections.unmodifiableList(
            ((List<Object>) map.getOrDefault("apm_configuration_rules", new ArrayList<>()))
                .stream().map(Rule::new).collect(Collectors.toList()));
  }

  public StableConfig(String configId, Map<String, Object> apmConfigurationDefault) {
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
}
