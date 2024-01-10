package datadog.trace.civisibility.config;

import java.nio.file.Path;

public class CiVisibilitySettings {

  public static final CiVisibilitySettings DEFAULT =
      new CiVisibilitySettings(false, false, false, false);

  private final boolean code_coverage;
  private final boolean tests_skipping;
  private final boolean require_git;
  private final boolean flaky_test_retries_enabled;

  public CiVisibilitySettings(
      boolean code_coverage,
      boolean tests_skipping,
      boolean require_git,
      boolean flaky_test_retries_enabled) {
    this.code_coverage = code_coverage;
    this.tests_skipping = tests_skipping;
    this.require_git = require_git;
    this.flaky_test_retries_enabled = flaky_test_retries_enabled;
  }

  public boolean isCodeCoverageEnabled() {
    return code_coverage;
  }

  public boolean isTestsSkippingEnabled() {
    return tests_skipping;
  }

  public boolean isGitUploadRequired() {
    return require_git;
  }

  public boolean isFlakyTestRetriesEnabled() {
    return flaky_test_retries_enabled;
  }

  public interface Factory {
    CiVisibilitySettings create(Path path);
  }
}
