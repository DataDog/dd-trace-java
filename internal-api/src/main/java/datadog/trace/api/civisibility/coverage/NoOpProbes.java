package datadog.trace.api.civisibility.coverage;

public class NoOpProbes implements CoverageProbes {
  public static final NoOpProbes INSTANCE = new NoOpProbes();

  private NoOpProbes() {}

  @Override
  public void record(Class<?> clazz) {}

  @Override
  public void record(Class<?> clazz, long classId, int probeId) {}

  @Override
  public void recordNonCodeResource(String absolutePath) {}
}
