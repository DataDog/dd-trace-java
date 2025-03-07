package datadog.trace.bootstrap.config.provider.StableConfigYaml;

import datadog.trace.bootstrap.config.provider.ConfigurationMap;
import java.util.List;

public class Rule {
  private List<Selector> selectors;
  private ConfigurationMap configuration;

  // Getters and setters
  public List<Selector> getSelectors() {
    return selectors;
  }

  public void setSelectors(List<Selector> selectors) {
    this.selectors = selectors;
  }

  public ConfigurationMap getConfiguration() {
    return configuration;
  }

  public void setConfiguration(ConfigurationMap configuration) {
    this.configuration = configuration;
  }
}
