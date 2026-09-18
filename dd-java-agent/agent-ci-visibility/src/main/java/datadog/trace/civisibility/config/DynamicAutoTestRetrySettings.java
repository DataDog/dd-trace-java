package datadog.trace.civisibility.config;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class DynamicAutoTestRetrySettings {

  public static final DynamicAutoTestRetrySettings DEFAULT =
      new DynamicAutoTestRetrySettings(false, false, Collections.emptyList());

  private static final long[] BUCKET_DURATIONS_MILLIS = {
    5_000, 10_000, 30_000, 300_000, Long.MAX_VALUE
  };

  private final boolean enabled;
  private final boolean custom;
  private final List<ExecutionsByDuration> executionsByDuration;

  private DynamicAutoTestRetrySettings(
      boolean enabled, boolean custom, List<ExecutionsByDuration> executionsByDuration) {
    this.enabled = enabled;
    this.custom = custom;
    this.executionsByDuration = Collections.unmodifiableList(new ArrayList<>(executionsByDuration));
  }

  public static DynamicAutoTestRetrySettings create(
      boolean enabled,
      List<Integer> customBuckets,
      List<ExecutionsByDuration> backendRetriesByDuration) {
    if (!enabled) {
      return DEFAULT;
    }
    List<ExecutionsByDuration> executionsByDuration = new ArrayList<>();
    if (customBuckets != null) {
      for (int i = 0; i < customBuckets.size(); i++) {
        executionsByDuration.add(
            new ExecutionsByDuration(BUCKET_DURATIONS_MILLIS[i], customBuckets.get(i) + 1));
      }
    } else {
      for (ExecutionsByDuration retries : backendRetriesByDuration) {
        executionsByDuration.add(
            new ExecutionsByDuration(
                retries.getDurationMillis(), Math.max(1, retries.getExecutions()) + 1));
      }
    }
    return new DynamicAutoTestRetrySettings(true, customBuckets != null, executionsByDuration);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isCustom() {
    return custom;
  }

  public int executionsForDuration(long durationMillis) {
    for (ExecutionsByDuration executions : executionsByDuration) {
      if (durationMillis <= executions.getDurationMillis()) {
        return executions.getExecutions();
      }
    }
    return 2;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DynamicAutoTestRetrySettings that = (DynamicAutoTestRetrySettings) o;
    return enabled == that.enabled
        && custom == that.custom
        && Objects.equals(executionsByDuration, that.executionsByDuration);
  }

  @Override
  public int hashCode() {
    return Objects.hash(enabled, custom, executionsByDuration);
  }

  public static final class Serializer {

    private static final int ENABLED_FLAG = 1;
    private static final int CUSTOM_FLAG = 2;

    public static void serialize(
        datadog.trace.civisibility.ipc.serialization.Serializer serializer,
        DynamicAutoTestRetrySettings settings) {
      byte flags =
          (byte) ((settings.enabled ? ENABLED_FLAG : 0) | (settings.custom ? CUSTOM_FLAG : 0));
      serializer.write(flags);
      serializer.write(settings.executionsByDuration, ExecutionsByDuration.Serializer::serialize);
    }

    public static DynamicAutoTestRetrySettings deserialize(ByteBuffer buffer) {
      byte flags = datadog.trace.civisibility.ipc.serialization.Serializer.readByte(buffer);
      List<ExecutionsByDuration> executionsByDuration =
          datadog.trace.civisibility.ipc.serialization.Serializer.readList(
              buffer, ExecutionsByDuration.Serializer::deserialize);
      return new DynamicAutoTestRetrySettings(
          (flags & ENABLED_FLAG) != 0, (flags & CUSTOM_FLAG) != 0, executionsByDuration);
    }
  }
}
