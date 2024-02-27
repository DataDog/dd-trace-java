package datadog.trace.api.civisibility.config;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

public class ModuleExecutionSettings {

  public static final ModuleExecutionSettings EMPTY =
      new ModuleExecutionSettings(
          false,
          false,
          false,
          EarlyFlakeDetectionSettings.DEFAULT,
          Collections.emptyMap(),
          null,
          Collections.emptyMap(),
          Collections.emptyList(),
          null,
          Collections.emptyList());

  private final boolean codeCoverageEnabled;
  private final boolean itrEnabled;
  private final boolean flakyTestRetriesEnabled;
  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  private final Map<String, String> systemProperties;
  private final String itrCorrelationId;
  private final Map<String, Collection<TestIdentifier>> skippableTestsByModule;
  private final Collection<TestIdentifier> flakyTests;
  @Nullable private final Map<String, Collection<TestIdentifier>> knownTestsByModule;
  private final List<String> coverageEnabledPackages;

  public ModuleExecutionSettings(
      boolean codeCoverageEnabled,
      boolean itrEnabled,
      boolean flakyTestRetriesEnabled,
      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings,
      Map<String, String> systemProperties,
      String itrCorrelationId,
      Map<String, Collection<TestIdentifier>> skippableTestsByModule,
      Collection<TestIdentifier> flakyTests,
      Map<String, Collection<TestIdentifier>> knownTestsByModule,
      List<String> coverageEnabledPackages) {
    this.codeCoverageEnabled = codeCoverageEnabled;
    this.itrEnabled = itrEnabled;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.systemProperties = systemProperties;
    this.itrCorrelationId = itrCorrelationId;
    this.skippableTestsByModule = skippableTestsByModule;
    this.flakyTests = flakyTests;
    this.knownTestsByModule = knownTestsByModule;
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

  public EarlyFlakeDetectionSettings getEarlyFlakeDetectionSettings() {
    return earlyFlakeDetectionSettings;
  }

  public Map<String, String> getSystemProperties() {
    return systemProperties;
  }

  @Nullable
  public String getItrCorrelationId() {
    return itrCorrelationId;
  }

  public Map<String, Collection<TestIdentifier>> getSkippableTestsByModule() {
    return skippableTestsByModule;
  }

  public Collection<TestIdentifier> getFlakyTests() {
    return flakyTests;
  }

  /**
   * @return known tests grouped by module (can be empty), or {@code null} if known tests could not
   *     be obtained
   */
  @Nullable
  public Map<String, Collection<TestIdentifier>> getKnownTestsByModule() {
    return knownTestsByModule;
  }

  public Collection<TestIdentifier> getSkippableTests(String moduleName) {
    return skippableTestsByModule.getOrDefault(moduleName, Collections.emptyList());
  }

  public Collection<TestIdentifier> getFlakyTests(String moduleName) {
    // backend does not store module info for flaky tests yet
    return flakyTests;
  }

  /**
   * @return the list of known tests for the given module (can be empty), or {@code null} if known
   *     tests could not be obtained
   */
  @Nullable
  public Collection<TestIdentifier> getKnownTests(String moduleName) {
    return knownTestsByModule != null
        ? knownTestsByModule.getOrDefault(moduleName, Collections.emptyList())
        : null;
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
        && Objects.equals(earlyFlakeDetectionSettings, that.earlyFlakeDetectionSettings)
        && Objects.equals(systemProperties, that.systemProperties)
        && Objects.equals(itrCorrelationId, that.itrCorrelationId)
        && Objects.equals(skippableTestsByModule, that.skippableTestsByModule)
        && Objects.equals(flakyTests, that.flakyTests)
        && Objects.equals(knownTestsByModule, that.knownTestsByModule)
        && Objects.equals(coverageEnabledPackages, that.coverageEnabledPackages);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        codeCoverageEnabled,
        itrEnabled,
        earlyFlakeDetectionSettings,
        systemProperties,
        itrCorrelationId,
        skippableTestsByModule,
        flakyTests,
        knownTestsByModule,
        coverageEnabledPackages);
  }
}
