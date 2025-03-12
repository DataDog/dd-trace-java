package datadog.trace.bootstrap.config.provider.StableConfigYaml;

import java.util.List;

// TODO: Update this comment from "stable configuration" to whatever product decides on for the name
// Rule represents a set of selectors and their corresponding configurations found in stable
// configuration files
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
