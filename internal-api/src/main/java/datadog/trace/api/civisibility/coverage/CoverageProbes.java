package datadog.trace.api.civisibility.coverage;

public interface CoverageProbes {
  void record(Class<?> clazz);

  void record(Class<?> clazz, long classId, int probeId);

  void recordNonCodeResource(String absolutePath);
}
