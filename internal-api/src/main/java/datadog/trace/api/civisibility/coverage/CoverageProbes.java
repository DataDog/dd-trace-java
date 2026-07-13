package datadog.trace.api.civisibility.coverage;

public interface CoverageProbes {
  void record(Class<?> clazz);

  /**
   * Resolves the probe array that Jacoco's instrumentation writes into at runtime. Called once per
   * instrumented method invocation, allowing per-test coverage to be captured by swapping Jacoco's
   * shared probe array for a test-scoped one. The default returns {@code jacocoProbes} unchanged so
   * that, when no per-test store is active, Jacoco's own aggregate coverage keeps working.
   *
   * @param clazz the class being executed
   * @param classId Jacoco's class identifier
   * @param jacocoProbes Jacoco's shared probe array for the class
   * @return the probe array to record coverage into
   */
  default boolean[] resolveProbeArray(Class<?> clazz, long classId, boolean[] jacocoProbes) {
    return jacocoProbes;
  }

  void recordNonCodeResource(String absolutePath);
}
