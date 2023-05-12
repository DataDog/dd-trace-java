package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.civisibility.source.SourcePathResolver;
import javax.annotation.Nullable;

public interface CoverageProbeStore {
  void record(Class<?> clazz, long classId, String className, int probeId);

  void report(Long testSessionId, Long testSuiteId, long spanId);

  @Nullable
  TestReport getReport();

  interface Factory {
    void setTotalProbeCount(String className, int totalProbeCount);

    CoverageProbeStore create(SourcePathResolver sourcePathResolver);
  }
}
