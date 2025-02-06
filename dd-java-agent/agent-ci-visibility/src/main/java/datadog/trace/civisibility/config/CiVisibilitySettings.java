package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public class CiVisibilitySettings {

  public static final CiVisibilitySettings DEFAULT =
      new CiVisibilitySettings(
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          EarlyFlakeDetectionSettings.DEFAULT,
          TestManagementSettings.DEFAULT);

  private final boolean itrEnabled;
  private final boolean codeCoverage;
  private final boolean testsSkipping;
  private final boolean requireGit;
  private final boolean flakyTestRetriesEnabled;
  private final boolean impactedTestsDetectionEnabled;
  private final boolean knownTestsEnabled;
  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  private final TestManagementSettings testManagementSettings;

  CiVisibilitySettings(
      boolean itrEnabled,
      boolean codeCoverage,
      boolean testsSkipping,
      boolean requireGit,
      boolean flakyTestRetriesEnabled,
      boolean impactedTestsDetectionEnabled,
      boolean knownTestsEnabled,
      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings,
      TestManagementSettings testManagementSettings) {
    this.itrEnabled = itrEnabled;
    this.codeCoverage = codeCoverage;
    this.testsSkipping = testsSkipping;
    this.requireGit = requireGit;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
    this.impactedTestsDetectionEnabled = impactedTestsDetectionEnabled;
    this.knownTestsEnabled = knownTestsEnabled;
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.testManagementSettings = testManagementSettings;
  }

  public boolean isItrEnabled() {
    return itrEnabled;
  }

  public boolean isCodeCoverageEnabled() {
    return codeCoverage;
  }

  public boolean isTestsSkippingEnabled() {
    return testsSkipping;
  }

  public boolean isGitUploadRequired() {
    return requireGit;
  }

  public boolean isFlakyTestRetriesEnabled() {
    return flakyTestRetriesEnabled;
  }

  public boolean isImpactedTestsDetectionEnabled() {
    return impactedTestsDetectionEnabled;
  }

  public boolean isKnownTestsEnabled() {
    return knownTestsEnabled;
  }

  public EarlyFlakeDetectionSettings getEarlyFlakeDetectionSettings() {
    return earlyFlakeDetectionSettings;
  }

  public TestManagementSettings getTestManagementSettings() {
    return testManagementSettings;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CiVisibilitySettings that = (CiVisibilitySettings) o;
    return itrEnabled == that.itrEnabled
        && codeCoverage == that.codeCoverage
        && testsSkipping == that.testsSkipping
        && requireGit == that.requireGit
        && flakyTestRetriesEnabled == that.flakyTestRetriesEnabled
        && impactedTestsDetectionEnabled == that.impactedTestsDetectionEnabled
        && knownTestsEnabled == that.knownTestsEnabled
        && Objects.equals(earlyFlakeDetectionSettings, that.earlyFlakeDetectionSettings)
        && Objects.equals(testManagementSettings, that.testManagementSettings);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        itrEnabled,
        codeCoverage,
        testsSkipping,
        requireGit,
        flakyTestRetriesEnabled,
        impactedTestsDetectionEnabled,
        knownTestsEnabled,
        earlyFlakeDetectionSettings,
        testManagementSettings);
  }

  public interface Factory {
    CiVisibilitySettings create(Path path);
  }

  public static final class JsonAdapter {

    public static final JsonAdapter INSTANCE = new JsonAdapter();

    @FromJson
    public CiVisibilitySettings fromJson(Map<String, Object> json) {
      if (json == null) {
        return DEFAULT;
      }

      return new CiVisibilitySettings(
          getBoolean(json, "itr_enabled", false),
          getBoolean(json, "code_coverage", false),
          getBoolean(json, "tests_skipping", false),
          getBoolean(json, "require_git", false),
          getBoolean(json, "flaky_test_retries_enabled", false),
          getBoolean(json, "impacted_tests_enabled", false),
          getBoolean(json, "known_tests_enabled", false),
          EarlyFlakeDetectionSettingsJsonAdapter.INSTANCE.fromJson(
              (Map<String, Object>) json.get("early_flake_detection")),
          TestManagementSettingsJsonAdapter.INSTANCE.fromJson(
              (Map<String, Object>) json.get("test_management")));
    }

    private static boolean getBoolean(
        Map<String, Object> json, String fieldName, boolean defaultValue) {
      Object value = json.get(fieldName);
      return value instanceof Boolean ? (Boolean) value : defaultValue;
    }
  }
}
