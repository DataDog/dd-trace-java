package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.civisibility.source.SourcePathResolver;
import javax.annotation.Nullable;

public class NoopCoverageProbeStore implements CoverageProbeStore {
  private static final CoverageProbeStore INSTANCE = new NoopCoverageProbeStore();

  @Override
  public void record(Class<?> clazz) {}

  @Override
  public void record(Class<?> clazz, long classId, int probeId) {}

  @Override
  public void recordNonCodeResource(String absolutePath) {}

  @Override
  public void report(Long testSessionId, Long testSuiteId, long spanId) {}

  @Nullable
  @Override
  public TestReport getReport() {
    return null;
  }

  public static final class NoopCoverageProbeStoreFactory implements CoverageProbeStoreFactory {
    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {}

    @Override
    public CoverageProbeStore create(SourcePathResolver sourcePathResolver) {
      return INSTANCE;
    }
  }
}
