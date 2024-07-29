package datadog.trace.civisibility.coverage.percentage;

import datadog.trace.api.civisibility.domain.ModuleLayout;
import javax.annotation.Nullable;

public interface CoverageCalculator {
  @Nullable
  Long calculateCoveragePercentage();

  interface Factory<T extends CoverageCalculator> {
    T sessionCoverage(long sessionId);

    T moduleCoverage(long moduleId, ModuleLayout moduleLayout, T sessionCoverage);
  }
}
