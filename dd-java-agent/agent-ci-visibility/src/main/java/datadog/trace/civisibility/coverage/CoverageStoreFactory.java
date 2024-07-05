package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.civisibility.source.SourcePathResolver;

public interface CoverageStoreFactory extends CoverageStore.Registry {
  CoverageStore create(TestIdentifier testIdentifier, SourcePathResolver sourcePathResolver);
}
