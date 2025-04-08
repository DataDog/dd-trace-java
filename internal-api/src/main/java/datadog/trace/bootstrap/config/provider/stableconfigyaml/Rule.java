package datadog.trace.bootstrap.config.provider.stableconfigyaml;

import java.util.ArrayList;
import java.util.List;

// Rule represents a set of selectors and their corresponding configurations found in stable
// configuration files
public class Rule {
  private List<Selector> selectors;
  private ConfigurationMap configuration;

  public Rule() {
    this.selectors = new ArrayList<>();
    this.configuration = new ConfigurationMap();
  }

  public Rule(List<Selector> selectors, ConfigurationMap configuration) {
    this.selectors = selectors;
    this.configuration = configuration;
  }

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
