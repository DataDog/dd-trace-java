package datadog.trace.civisibility.config;

import java.nio.file.Path;

public class CiVisibilitySettings {

  private final boolean code_coverage;
  private final boolean tests_skipping;
  private final boolean require_git;

  public CiVisibilitySettings(boolean code_coverage, boolean tests_skipping, boolean require_git) {
    this.code_coverage = code_coverage;
    this.tests_skipping = tests_skipping;
    this.require_git = require_git;
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

  public interface Factory {
    CiVisibilitySettings create(Path path);
  }
}
