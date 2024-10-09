package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestIdentifier;
import javax.annotation.Nullable;

public class NoOpCoverageStore implements CoverageStore {

  public static final NoOpCoverageStore INSTANCE = new NoOpCoverageStore();

  private NoOpCoverageStore() {}

  @Override
  public CoverageProbes getProbes() {
    return NoOpProbes.INSTANCE;
  }

  @Override
  public boolean report(DDTraceId testSessionId, Long testSuiteId, long testSpanId) {
    return true;
  }

  @Nullable
  @Override
  public TestReport getReport() {
    return null;
  }

  public static final class Factory implements CoverageStore.Factory {
    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return INSTANCE;
    }

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      // no op
    }
  }
}
