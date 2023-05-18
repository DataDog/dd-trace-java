package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.source.SourcePathResolver;

public interface CoverageProbeStore {
  void record(Class<?> clazz, long classId, String className, int probeId);

  void report(Long testSessionId, long testModuleId, long testSuiteId, long spanId);

  interface Factory {
    void setTotalProbeCount(String className, int totalProbeCount);

    CoverageProbeStore create(SourcePathResolver sourcePathResolver);
  }
}
