package datadog.trace.api.civisibility.coverage;

public interface CoverageProbeStore {
  void record(long classId, String className, int probeId);

  void report(Long testSessionId, long testSuiteId, long spanId);

  interface Factory {
    void setTotalProbeCount(String className, int totalProbeCount);

    CoverageProbeStore create();
  }
}
