package datadog.trace.agent.test.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;

public class NoopCoverageProbeStore implements CoverageProbeStore {
  private static final CoverageProbeStore INSTANCE = new NoopCoverageProbeStore();

  @Override
  public void record(long classId, String className, int probeId) {}

  @Override
  public void report(Long testSessionId, long testSuiteId, long spanId) {}

  public static final class NoopCoverageProbeStoreFactory implements CoverageProbeStore.Factory {
    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {}

    @Override
    public CoverageProbeStore create() {
      return INSTANCE;
    }
  }
}
