package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.config.TestIdentifier;

public interface CoverageStore extends TestReportHolder {

  CoverageProbes getProbes();

  /** @return {@code true} if coverage was gathered successfully */
  boolean report(Long testSessionId, Long testSuiteId, long testSpanId);

  interface Factory extends Registry {
    CoverageStore create(TestIdentifier testIdentifier);
  }

  interface Registry {
    void setTotalProbeCount(String className, int totalProbeCount);
  }
}
