package datadog.trace.api.civisibility.coverage;

public interface CoverageProbeStore extends TestReportHolder {
  void record(Class<?> clazz, long classId, String className, int probeId);

  void report(Long testSessionId, Long testSuiteId, long spanId);

  interface Registry {
    void setTotalProbeCount(String className, int totalProbeCount);
  }
}
