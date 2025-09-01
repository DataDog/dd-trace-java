package datadog.trace.civisibility.coverage.report;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.civisibility.config.ExecutionSettings;
import javax.annotation.Nullable;

/** Processes coverage reports. */
public interface CoverageProcessor {
  /** Processes previously collected coverage data and returns the percentage of lines covered. */
  @Nullable
  Long processCoverageData();

  interface Factory<T extends CoverageProcessor> {
    T sessionCoverage(long sessionId);

    T moduleCoverage(
        long moduleId,
        @Nullable BuildModuleLayout moduleLayout,
        ExecutionSettings executionSettings,
        T sessionCoverage);
  }
}
