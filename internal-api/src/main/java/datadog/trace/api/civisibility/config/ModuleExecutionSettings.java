package datadog.trace.api.civisibility.config;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModuleExecutionSettings {

  private final boolean codeCoverageEnabled;
  private final boolean itrEnabled;
  private final Map<String, String> systemProperties;
  private final Map<String, List<SkippableTest>> skippableTestsByModule;
  private final List<String> coverageEnabledPackages;

  public ModuleExecutionSettings(
      boolean codeCoverageEnabled,
      boolean itrEnabled,
      Map<String, String> systemProperties,
      Map<String, List<SkippableTest>> skippableTestsByModule,
      List<String> coverageEnabledPackages) {
    this.codeCoverageEnabled = codeCoverageEnabled;
    this.itrEnabled = itrEnabled;
    this.systemProperties = systemProperties;
    this.skippableTestsByModule = skippableTestsByModule;
    this.coverageEnabledPackages = coverageEnabledPackages;
  }

  public boolean isCodeCoverageEnabled() {
    return codeCoverageEnabled;
  }

  public boolean isItrEnabled() {
    return itrEnabled;
  }

  public Map<String, String> getSystemProperties() {
    return systemProperties;
  }

  public Collection<SkippableTest> getSkippableTests(String relativeModulePath) {
    return skippableTestsByModule.getOrDefault(relativeModulePath, Collections.emptyList());
  }

  public List<String> getCoverageEnabledPackages() {
    return coverageEnabledPackages;
  }
}
