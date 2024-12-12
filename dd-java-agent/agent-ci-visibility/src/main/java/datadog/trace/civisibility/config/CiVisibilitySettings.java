package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import java.nio.file.Path;
import java.util.Map;

public class CiVisibilitySettings {

  public static final CiVisibilitySettings DEFAULT =
      new CiVisibilitySettings(
          false, false, false, false, false, EarlyFlakeDetectionSettings.DEFAULT);

  private final boolean itrEnabled;
  private final boolean codeCoverage;
  private final boolean testsSkipping;
  private final boolean requireGit;
  private final boolean flakyTestRetriesEnabled;
  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;

  private CiVisibilitySettings(
      boolean itrEnabled,
      boolean codeCoverage,
      boolean testsSkipping,
      boolean requireGit,
      boolean flakyTestRetriesEnabled,
      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings) {
    this.itrEnabled = itrEnabled;
    this.codeCoverage = codeCoverage;
    this.testsSkipping = testsSkipping;
    this.requireGit = requireGit;
    this.flakyTestRetriesEnabled = flakyTestRetriesEnabled;
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
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

  public EarlyFlakeDetectionSettings getEarlyFlakeDetectionSettings() {
    return earlyFlakeDetectionSettings;
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
          EarlyFlakeDetectionSettingsJsonAdapter.INSTANCE.fromJson(
              (Map<String, Object>) json.get("early_flake_detection")));
    }

    private static boolean getBoolean(
        Map<String, Object> json, String fieldName, boolean defaultValue) {
      Object value = json.get(fieldName);
      return value instanceof Boolean ? (Boolean) value : defaultValue;
    }
  }
}
