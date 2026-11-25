package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestIdentifier;
import javax.annotation.Nullable;

public interface CoverageStore extends TestReportHolder {

  CoverageProbes getProbes();

  /**
   * @return {@code true} if coverage was gathered successfully
   */
  boolean report(DDTraceId testSessionId, Long testSuiteId, long testSpanId);

  /** Called instead of {@link #report} for a test whose coverage is not reported. */
  default void discard() {}

  interface Factory extends Registry {
    CoverageStore create(@Nullable TestIdentifier testIdentifier);
  }

  interface Registry {
    void setTotalProbeCount(String className, int totalProbeCount);
  }
}
