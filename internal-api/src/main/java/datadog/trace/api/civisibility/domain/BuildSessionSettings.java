package datadog.trace.api.civisibility.domain;

import java.util.List;

public class BuildSessionSettings {

  private final boolean coverageReportUploadEnabled;
  private final List<String> coverageIncludedPackages;
  private final List<String> coverageExcludedPackages;

  public BuildSessionSettings(
      boolean coverageReportUploadEnabled,
      List<String> coverageIncludedPackages,
      List<String> coverageExcludedPackages) {
    this.coverageReportUploadEnabled = coverageReportUploadEnabled;
    this.coverageIncludedPackages = coverageIncludedPackages;
    this.coverageExcludedPackages = coverageExcludedPackages;
  }

  public boolean isCoverageReportUploadEnabled() {
    return coverageReportUploadEnabled;
  }

  public List<String> getCoverageIncludedPackages() {
    return coverageIncludedPackages;
  }

  public List<String> getCoverageExcludedPackages() {
    return coverageExcludedPackages;
  }
}
