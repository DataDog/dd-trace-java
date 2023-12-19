package datadog.trace.api.civisibility.coverage;

public interface CoverageProbeStore extends TestReportHolder {
  void record(Class<?> clazz);

  void record(Class<?> clazz, long classId, int probeId);

  void recordNonCodeResource(String absolutePath);

  void report(Long testSessionId, Long testSuiteId, long spanId);

  interface Registry {
    void setTotalProbeCount(String className, int totalProbeCount);
  }
}
