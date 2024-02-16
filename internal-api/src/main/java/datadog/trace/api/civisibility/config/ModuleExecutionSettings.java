package datadog.trace.api.civisibility.config;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ModuleExecutionSettings {

  public static final ModuleExecutionSettings EMPTY =
      new ModuleExecutionSettings(
          false,
          false,
          false,
          Collections.emptyMap(),
          Collections.emptyMap(),
          Collections.emptyList(),
          Collections.emptyList());

  private final boolean codeCoverageEnabled;
  private final boolean itrEnabled;
  private final boolean flakyTestRetriesEnabled;
  private final Map<String, String> systemProperties;
  private final Map<String, Collection<TestIdentifier>> skippableTestsByModule;
  private final Collection<TestIdentifier> flakyTests;
  private final List<String> coverageEnabledPackages;

  public ModuleExecutionSettings(
      boolean codeCoverageEnabled,
      boolean itrEnabled,
      boolean flakyTestRetriesEnabled,
      Map<String, String> systemProperties,
      Map<String, Collection<TestIdentifier>> skippableTestsByModule,
      Collection<TestIdentifier> flakyTests,
      List<String> coverageEnabledPackages) {
    this.codeCoverageEnabled = codeCoverageEnabled;
    this.itrEnabled = itrEnabled;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
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

  public boolean isFlakyTestRetriesEnabled() {
    return flakyTestRetriesEnabled;
  }

  public Map<String, String> getSystemProperties() {
    return systemProperties;
  }

  public Map<String, Collection<TestIdentifier>> getSkippableTestsByModule() {
    return skippableTestsByModule;
  }

  public Collection<TestIdentifier> getFlakyTests() {
    return flakyTests;
  }

  public Collection<TestIdentifier> getSkippableTests(String moduleName) {
    return skippableTestsByModule.getOrDefault(moduleName, Collections.emptyList());
  }

  public Collection<TestIdentifier> getFlakyTests(String moduleName) {
    // backend does not store module info for flaky tests
    return flakyTests;
  }

  public List<String> getCoverageEnabledPackages() {
    return coverageEnabledPackages;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ModuleExecutionSettings that = (ModuleExecutionSettings) o;
    return codeCoverageEnabled == that.codeCoverageEnabled
        && itrEnabled == that.itrEnabled
        && Objects.equals(systemProperties, that.systemProperties)
        && Objects.equals(skippableTestsByModule, that.skippableTestsByModule)
        && Objects.equals(flakyTests, that.flakyTests)
        && Objects.equals(coverageEnabledPackages, that.coverageEnabledPackages);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        codeCoverageEnabled,
        itrEnabled,
        systemProperties,
        skippableTestsByModule,
        flakyTests,
        coverageEnabledPackages);
  }
}
