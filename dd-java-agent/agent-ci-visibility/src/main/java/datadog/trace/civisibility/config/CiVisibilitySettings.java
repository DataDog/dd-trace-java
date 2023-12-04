package datadog.trace.civisibility.config;

import java.nio.file.Path;

public class CiVisibilitySettings {

  private final boolean code_coverage;
  private final boolean tests_skipping;

  public CiVisibilitySettings(boolean code_coverage, boolean tests_skipping) {
    this.code_coverage = code_coverage;
    this.tests_skipping = tests_skipping;
  }

  public boolean isCodeCoverageEnabled() {
    return code_coverage;
  }

  public boolean isTestsSkippingEnabled() {
    return tests_skipping;
  }

  public interface Factory {
    CiVisibilitySettings create(Path path);
  }
}
