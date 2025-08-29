package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

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
          false,
          EarlyFlakeDetectionSettings.DEFAULT,
          TestManagementSettings.DEFAULT,
          null);

  private final boolean itrEnabled;
  private final boolean codeCoverage;
  private final boolean testsSkipping;
  private final boolean requireGit;
  private final boolean flakyTestRetriesEnabled;
  private final boolean impactedTestsDetectionEnabled;
  private final boolean knownTestsEnabled;
  private final boolean coverageReportUploadEnabled;
  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  private final TestManagementSettings testManagementSettings;
  @Nullable private final String defaultBranch;

  CiVisibilitySettings(
      boolean itrEnabled,
      boolean codeCoverage,
      boolean testsSkipping,
      boolean requireGit,
      boolean flakyTestRetriesEnabled,
      boolean impactedTestsDetectionEnabled,
      boolean knownTestsEnabled,
      boolean coverageReportUploadEnabled,
      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings,
      TestManagementSettings testManagementSettings,
      @Nullable String defaultBranch) {
    this.itrEnabled = itrEnabled;
    this.codeCoverage = codeCoverage;
    this.testsSkipping = testsSkipping;
    this.requireGit = requireGit;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
    this.impactedTestsDetectionEnabled = impactedTestsDetectionEnabled;
    this.knownTestsEnabled = knownTestsEnabled;
    this.coverageReportUploadEnabled = coverageReportUploadEnabled;
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.testManagementSettings = testManagementSettings;
    this.defaultBranch = defaultBranch;
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

  public boolean isCoverageReportUploadEnabled() {
    return coverageReportUploadEnabled;
  }

  public EarlyFlakeDetectionSettings getEarlyFlakeDetectionSettings() {
    return earlyFlakeDetectionSettings;
  }

  public TestManagementSettings getTestManagementSettings() {
    return testManagementSettings;
  }

  @Nullable
  public String getDefaultBranch() {
    return defaultBranch;
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
        && coverageReportUploadEnabled == that.coverageReportUploadEnabled
        && Objects.equals(earlyFlakeDetectionSettings, that.earlyFlakeDetectionSettings)
        && Objects.equals(testManagementSettings, that.testManagementSettings)
        && Objects.equals(defaultBranch, that.defaultBranch);
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
        coverageReportUploadEnabled,
        earlyFlakeDetectionSettings,
        testManagementSettings,
        defaultBranch);
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
          getBoolean(json, "coverage_report_upload_enabled", false),
          EarlyFlakeDetectionSettings.JsonAdapter.INSTANCE.fromJson(
              (Map<String, Object>) json.get("early_flake_detection")),
          TestManagementSettings.JsonAdapter.INSTANCE.fromJson(
              (Map<String, Object>) json.get("test_management")),
          getString(json, "default_branch", null));
    }

    private static boolean getBoolean(
        Map<String, Object> json, String fieldName, boolean defaultValue) {
      Object value = json.get(fieldName);
      return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    private static String getString(
        Map<String, Object> json, String fieldName, String defaultValue) {
      Object value = json.get(fieldName);
      return value instanceof String ? (String) value : defaultValue;
    }
  }
}
