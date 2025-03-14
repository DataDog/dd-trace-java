package datadog.trace.civisibility.coverage.percentage;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.civisibility.config.ExecutionSettings;
import javax.annotation.Nullable;

/** Calculates percentage of executable lines that are covered with tests. */
public interface CoverageCalculator {
  @Nullable
  Long calculateCoveragePercentage();

  interface Factory<T extends CoverageCalculator> {
    T sessionCoverage(long sessionId);

    T moduleCoverage(
        long moduleId,
        @Nullable BuildModuleLayout moduleLayout,
        ExecutionSettings executionSettings,
        T sessionCoverage);
  }
}
