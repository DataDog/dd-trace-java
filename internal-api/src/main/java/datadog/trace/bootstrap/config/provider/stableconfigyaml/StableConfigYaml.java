package datadog.trace.bootstrap.config.provider.stableconfigyaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StableConfigYaml {
  private String config_id; // optional
  private Map<String, Object> apm_configuration_default;
  private List<Rule> apm_configuration_rules; // optional

  public StableConfigYaml(Object yaml) {
    Map<Object, Object> map = (Map<Object, Object>) yaml;
    this.config_id = String.valueOf(map.get("config_id"));
    this.apm_configuration_default =
        (Map<String, Object>) map.getOrDefault("apm_configuration_default", new LinkedHashMap<>());
    this.apm_configuration_rules =
        ((List<Object>) map.getOrDefault("apm_configuration_rules", new ArrayList<>()))
            .stream().map(Rule::new).collect(Collectors.toList());
  }

  public StableConfigYaml() {
    this.config_id = null;
    this.apm_configuration_default = new LinkedHashMap<>();
    this.apm_configuration_rules = new ArrayList<>();
  }

  public String getConfig_id() {
    return config_id;
  }

  public void setConfig_id(String config_id) {
    this.config_id = config_id;
  }

  public Map<String, Object> getApm_configuration_default() {
    return apm_configuration_default;
  }

  public void setApm_configuration_default(Map<String, Object> apm_configuration_default) {
    this.apm_configuration_default = apm_configuration_default;
  }

  public List<Rule> getApm_configuration_rules() {
    return apm_configuration_rules;
  }
}
