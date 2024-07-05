package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.civisibility.source.SourcePathResolver;
import javax.annotation.Nullable;

public class NoopCoverageStore implements CoverageStore {
  public static final CoverageStore INSTANCE = new NoopCoverageStore();

  @Override
  public void record(Class<?> clazz) {}

  @Override
  public void record(Class<?> clazz, long classId, int probeId) {}

  @Override
  public void recordNonCodeResource(String absolutePath) {}

  @Override
  public boolean report(Long testSessionId, Long testSuiteId, long spanId) {
    return true;
  }

  @Nullable
  @Override
  public TestReport getReport() {
    return null;
  }

  public static final class NoopCoverageProbeStoreFactory implements CoverageStoreFactory {
    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {}

    @Override
    public CoverageStore create(
        TestIdentifier testIdentifier, SourcePathResolver sourcePathResolver) {
      return INSTANCE;
    }
  }
}
