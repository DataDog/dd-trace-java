package datadog.trace.api.civisibility.config;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModuleExecutionSettings {

  private final Map<String, String> systemProperties;
  private final Map<String, List<SkippableTest>> skippableTestsByModule;

  public ModuleExecutionSettings(
      Map<String, String> systemProperties,
      Map<String, List<SkippableTest>> skippableTestsByModule) {
    this.systemProperties = systemProperties;
    this.skippableTestsByModule = skippableTestsByModule;
  }

  public Map<String, String> getSystemProperties() {
    return systemProperties;
  }

  public Collection<SkippableTest> getSkippableTests(String relativeModulePath) {
    return skippableTestsByModule.getOrDefault(relativeModulePath, Collections.emptyList());
  }
}
