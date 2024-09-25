package datadog.trace.api.civisibility.domain;

import java.util.List;

public class BuildSessionSettings {

  private final List<String> coverageEnabledPackages;

  public BuildSessionSettings(List<String> coverageEnabledPackages) {
    this.coverageEnabledPackages = coverageEnabledPackages;
  }

  public List<String> getCoverageEnabledPackages() {
    return coverageEnabledPackages;
  }
}
