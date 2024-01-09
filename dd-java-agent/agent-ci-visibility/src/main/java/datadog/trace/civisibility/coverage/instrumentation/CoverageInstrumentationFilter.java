package datadog.trace.civisibility.coverage.instrumentation;

import java.util.function.Predicate;

public final class CoverageInstrumentationFilter implements Predicate<String> {
  private final String[] includedPackages;
  private final String[] excludedPackages;

  public CoverageInstrumentationFilter(String[] includedPackages, String[] excludedPackages) {
    this.includedPackages = includedPackages;
    this.excludedPackages = excludedPackages;
  }

  @Override
  public boolean test(String className) {
    for (String excludedPackage : excludedPackages) {
      if (className.startsWith(excludedPackage)) {
        return false;
      }
    }
    for (String includedPackage : includedPackages) {
      if (className.startsWith(includedPackage)) {
        return true;
      }
    }
    return false;
  }
}
