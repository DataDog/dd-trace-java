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

  /**
   * Whether {@link #report} is also called for a skipped test, so that a test that skipped itself
   * after it started reports the lines it ran.
   */
  default boolean reportsSkippedTests() {
    return false;
  }

  /**
   * The name of the collector that gathers this store's coverage, which the test reports, or {@code
   * null} if the store gathers none.
   */
  @Nullable
  default String getCollectorName() {
    return null;
  }

  interface Factory extends Registry {
    CoverageStore create(@Nullable TestIdentifier testIdentifier);
  }

  interface Registry {
    void setTotalProbeCount(String className, int totalProbeCount);
  }
}
