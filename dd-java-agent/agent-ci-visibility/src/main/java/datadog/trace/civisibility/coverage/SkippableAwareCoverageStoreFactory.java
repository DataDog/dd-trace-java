package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * Coverage store factory that returns no-op stores for skippable tests. This is done to reduce
 * coverage overhead. The idea is that if a test is skippable then it means none of the files it
 * covers were changed. If none of the files were changed then gathering coverage for the test make
 * no sense, because it will be the same as previously gathered coverage that the backend already
 * has.
 */
public class SkippableAwareCoverageStoreFactory implements CoverageStore.Factory {
  private final Collection<TestIdentifier> skippableTests;
  private final CoverageStore.Factory delegate;

  public SkippableAwareCoverageStoreFactory(
      Collection<TestIdentifier> skippableTests, CoverageStore.Factory delegate) {
    this.skippableTests = skippableTests;
    this.delegate = delegate;
  }

  @Override
  public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
    if (skippableTests.contains(testIdentifier)) {
      return NoOpCoverageStore.INSTANCE;
    } else {
      return delegate.create(testIdentifier);
    }
  }

  @Override
  public void setTotalProbeCount(String className, int totalProbeCount) {
    delegate.setTotalProbeCount(className, totalProbeCount);
  }
}
