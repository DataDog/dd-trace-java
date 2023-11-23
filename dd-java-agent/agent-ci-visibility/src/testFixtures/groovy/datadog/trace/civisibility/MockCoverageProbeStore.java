package datadog.trace.civisibility;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.ArrayList;
import java.util.Collection;

public class MockCoverageProbeStore implements CoverageProbeStore {
  public static final MockCoverageProbeStore INSTANCE = new MockCoverageProbeStore();

  private final Collection<String> nonCodeResources = new ArrayList<>();

  @Override
  public void record(Class<?> clazz, long classId, String className, int probeId) {}

  @Override
  public void recordNonCodeResource(String absolutePath) {
    nonCodeResources.add(absolutePath);
  }

  @Override
  public void report(Long testSessionId, Long testSuiteId, long spanId) {}

  @Override
  public TestReport getReport() {
    return null;
  }

  public Collection<String> getNonCodeResources() {
    return nonCodeResources;
  }

  public void resetNonCodeResources() {
    nonCodeResources.clear();
  }

  public static final class MockCoverageProbeStoreFactory implements CoverageProbeStoreFactory {
    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {}

    @Override
    public CoverageProbeStore create(SourcePathResolver sourcePathResolver) {
      return MockCoverageProbeStore.INSTANCE;
    }
  }
}
