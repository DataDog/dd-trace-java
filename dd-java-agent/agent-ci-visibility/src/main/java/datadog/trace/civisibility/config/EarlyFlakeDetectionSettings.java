package datadog.trace.civisibility.config;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class EarlyFlakeDetectionSettings {

  public static final EarlyFlakeDetectionSettings DEFAULT =
      new EarlyFlakeDetectionSettings(false, Collections.emptyList(), -1);

  private final boolean enabled;
  private final List<ExecutionsByDuration> executionsByDuration;
  private final int faultySessionThreshold;

  public EarlyFlakeDetectionSettings(
      boolean enabled,
      List<ExecutionsByDuration> executionsByDuration,
      int faultySessionThreshold) {
    this.enabled = enabled;
    this.executionsByDuration = executionsByDuration;
    this.faultySessionThreshold = faultySessionThreshold;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getFaultySessionThreshold() {
    return faultySessionThreshold;
  }

  public List<ExecutionsByDuration> getExecutionsByDuration() {
    return executionsByDuration;
  }

  public int getExecutions(long durationMillis) {
    for (ExecutionsByDuration e : executionsByDuration) {
      if (durationMillis <= e.durationMillis) {
        return e.executions;
      }
    }
    return 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    EarlyFlakeDetectionSettings that = (EarlyFlakeDetectionSettings) o;
    return enabled == that.enabled
        && faultySessionThreshold == that.faultySessionThreshold
        && Objects.equals(executionsByDuration, that.executionsByDuration);
  }

  @Override
  public int hashCode() {
    return Objects.hash(enabled, executionsByDuration, faultySessionThreshold);
  }

  public static final class ExecutionsByDuration {
    public final long durationMillis;
    public final int executions;

    public ExecutionsByDuration(long durationMillis, int executions) {
      this.durationMillis = durationMillis;
      this.executions = executions;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ExecutionsByDuration that = (ExecutionsByDuration) o;
      return durationMillis == that.durationMillis && executions == that.executions;
    }

    @Override
    public int hashCode() {
      return Objects.hash(durationMillis, executions);
    }
  }
}
