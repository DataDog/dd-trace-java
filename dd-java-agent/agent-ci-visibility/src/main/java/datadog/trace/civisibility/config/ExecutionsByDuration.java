package datadog.trace.civisibility.config;

import java.nio.ByteBuffer;
import datadog.trace.util.HashingUtils;

public final class ExecutionsByDuration {
  public final long durationMillis;
  public final int executions;

  public ExecutionsByDuration(long durationMillis, int executions) {
    this.durationMillis = durationMillis;
    this.executions = executions;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public int getExecutions() {
    return executions;
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
    return HashingUtils.hash(durationMillis, executions);
  }

  public static class Serializer {
    public static void serialize(
        datadog.trace.civisibility.ipc.serialization.Serializer serializer,
        ExecutionsByDuration executionsByDuration) {
      serializer.write(executionsByDuration.durationMillis);
      serializer.write(executionsByDuration.executions);
    }

    public static ExecutionsByDuration deserialize(ByteBuffer buf) {
      return new ExecutionsByDuration(
          datadog.trace.civisibility.ipc.serialization.Serializer.readLong(buf),
          datadog.trace.civisibility.ipc.serialization.Serializer.readInt(buf));
    }
  }
}
