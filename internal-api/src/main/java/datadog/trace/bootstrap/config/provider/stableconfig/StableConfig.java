package datadog.trace.bootstrap.config.provider.stableconfig;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.toList;

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
    this.apmConfigurationDefault =
        unmodifiableMap(
            (Map<String, Object>) map.getOrDefault("apm_configuration_default", emptyMap()));
    this.apmConfigurationRules =
        unmodifiableList(
            ((List<Object>) map.getOrDefault("apm_configuration_rules", emptyList()))
                .stream().map(Rule::new).collect(toList()));
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
}
