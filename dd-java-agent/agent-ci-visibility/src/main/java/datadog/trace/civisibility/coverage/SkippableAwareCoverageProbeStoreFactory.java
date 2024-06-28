package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.Collection;

/**
 * Coverage store factory returns no-op stores for skippable tests. This is done to reduce coverage
 * overhead. The idea is that if a test is skippable then it means none of the files it covers were
 * changed. If none of the files were changed then gathering coverage for the test make no sense,
 * because it will be the same as previously gathered coverage that the backend already has.
 */
public class SkippableAwareCoverageProbeStoreFactory implements CoverageProbeStoreFactory {
  private final Collection<TestIdentifier> skippableTests;
  private final CoverageProbeStoreFactory delegate;

  public SkippableAwareCoverageProbeStoreFactory(
      Collection<TestIdentifier> skippableTests, CoverageProbeStoreFactory delegate) {
    this.skippableTests = skippableTests;
    this.delegate = delegate;
  }

  @Override
  public CoverageProbeStore create(
      TestIdentifier testIdentifier, SourcePathResolver sourcePathResolver) {
    if (skippableTests.contains(testIdentifier)) {
      return NoopCoverageProbeStore.INSTANCE;
    } else {
      return delegate.create(testIdentifier, sourcePathResolver);
    }
  }

  @Override
  public void setTotalProbeCount(String className, int totalProbeCount) {
    delegate.setTotalProbeCount(className, totalProbeCount);
  }
}
