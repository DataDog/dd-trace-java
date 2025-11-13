package datadog.trace.civisibility.coverage.report;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.civisibility.config.ExecutionSettings;
import javax.annotation.Nullable;

public class NoOpCoverageProcessor implements CoverageProcessor {

  private static final NoOpCoverageProcessor INSTANCE = new NoOpCoverageProcessor();

  private NoOpCoverageProcessor() {}

  @Nullable
  @Override
  public Long processCoverageData() {
    return null;
  }

  public static final class Factory implements CoverageProcessor.Factory<NoOpCoverageProcessor> {
    @Override
    public NoOpCoverageProcessor sessionCoverage(long sessionId) {
      return INSTANCE;
    }

    @Override
    public NoOpCoverageProcessor moduleCoverage(
        long moduleId,
        BuildModuleLayout moduleLayout,
        ExecutionSettings executionSettings,
        NoOpCoverageProcessor sessionCoverage) {
      return INSTANCE;
    }
  }
}
