package datadog.trace.bootstrap.config.provider.stableconfigyaml;

import java.util.ArrayList;
import java.util.List;

public class StableConfigYaml {
  private String config_id; // optional
  private ConfigurationMap apm_configuration_default;
  private List<Rule> apm_configuration_rules; // optional

  public StableConfigYaml() {
    this.config_id = null;
    this.apm_configuration_default = new ConfigurationMap();
    this.apm_configuration_rules = new ArrayList<>();
  }

  // Getters and setters
  public String getConfig_id() {
    return config_id;
  }

  public void setConfig_id(String config_id) {
    this.config_id = config_id;
  }

  public ConfigurationMap getApm_configuration_default() {
    return apm_configuration_default;
  }

  public void setApm_configuration_default(ConfigurationMap apm_configuration_default) {
    this.apm_configuration_default = apm_configuration_default;
  }

  public List<Rule> getApm_configuration_rules() {
    return apm_configuration_rules;
  }

  public void setApm_configuration_rules(List<Rule> apm_configuration_rules) {
    this.apm_configuration_rules = apm_configuration_rules;
  }
}
