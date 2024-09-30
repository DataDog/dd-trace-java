package datadog.trace.civisibility.coverage.percentage;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.civisibility.config.ExecutionSettings;
import org.jetbrains.annotations.Nullable;

public class NoOpCoverageCalculator implements CoverageCalculator {

  private static final NoOpCoverageCalculator INSTANCE = new NoOpCoverageCalculator();

  private NoOpCoverageCalculator() {}

  @Nullable
  @Override
  public Long calculateCoveragePercentage() {
    return null;
  }

  public static final class Factory implements CoverageCalculator.Factory<NoOpCoverageCalculator> {
    @Override
    public NoOpCoverageCalculator sessionCoverage(long sessionId) {
      return INSTANCE;
    }

    @Override
    public NoOpCoverageCalculator moduleCoverage(
        long moduleId,
        BuildModuleLayout moduleLayout,
        ExecutionSettings executionSettings,
        NoOpCoverageCalculator sessionCoverage) {
      return INSTANCE;
    }
  }
}
