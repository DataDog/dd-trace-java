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
   * Tells the store the id of its test's trace, once the test's span exists. A store that follows
   * the code each thread runs credits the test with the code that runs under any span of that
   * trace.
   */
  default void setTestTraceId(DDTraceId testTraceId) {}

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

    /**
     * Reports the lines that the process ran and that no test owns, once all tests of the process
     * ran.
     */
    default void reportShared(DDTraceId testSessionId) {}
  }

  interface Registry {
    void setTotalProbeCount(String className, int totalProbeCount);
  }
}
