package datadog.trace.api.civisibility.config;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModuleExecutionSettings {

  private final boolean codeCoverageEnabled;
  private final boolean itrEnabled;
  private final Map<String, String> systemProperties;
  private final Map<String, List<TestIdentifier>> skippableTestsByModule;
  private final Collection<TestIdentifier> flakyTests;
  private final List<String> coverageEnabledPackages;

  public ModuleExecutionSettings(
      boolean codeCoverageEnabled,
      boolean itrEnabled,
      Map<String, String> systemProperties,
      Map<String, List<TestIdentifier>> skippableTestsByModule,
      Collection<TestIdentifier> flakyTests,
      List<String> coverageEnabledPackages) {
    this.codeCoverageEnabled = codeCoverageEnabled;
    this.itrEnabled = itrEnabled;
    this.systemProperties = systemProperties;
    this.skippableTestsByModule = skippableTestsByModule;
    this.flakyTests = flakyTests;
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

  public Collection<TestIdentifier> getSkippableTests(String relativeModulePath) {
    return skippableTestsByModule.getOrDefault(relativeModulePath, Collections.emptyList());
  }

  public Collection<TestIdentifier> getFlakyTests(String relativeModulePath) {
    // backend does not store module info for flaky tests
    return flakyTests;
  }

  public List<String> getCoverageEnabledPackages() {
    return coverageEnabledPackages;
  }
}
