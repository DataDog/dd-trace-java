package datadog.trace.api.civisibility.domain;

import java.util.List;

public class BuildSessionSettings {

  private final List<String> coverageIncludedPackages;
  private final List<String> coverageExcludedPackages;

  public BuildSessionSettings(
      List<String> coverageIncludedPackages, List<String> coverageExcludedPackages) {
    this.coverageIncludedPackages = coverageIncludedPackages;
    this.coverageExcludedPackages = coverageExcludedPackages;
  }

  public List<String> getCoverageIncludedPackages() {
    return coverageIncludedPackages;
  }

  public List<String> getCoverageExcludedPackages() {
    return coverageExcludedPackages;
  }
}
