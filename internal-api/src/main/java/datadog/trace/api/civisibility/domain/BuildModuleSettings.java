package datadog.trace.api.civisibility.domain;

import java.util.Map;

public class BuildModuleSettings {

  private final Map<String, String> systemProperties;

  public BuildModuleSettings(Map<String, String> systemProperties) {
    this.systemProperties = systemProperties;
  }

  public Map<String, String> getSystemProperties() {
    return systemProperties;
  }
}
