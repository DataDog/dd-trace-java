package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.civisibility.source.SourcePathResolver;

public interface CoverageProbeStoreFactory extends CoverageProbeStore.Registry {
  CoverageProbeStore create(SourcePathResolver sourcePathResolver);
}
