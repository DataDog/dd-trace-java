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
  private final List<ExecutionsByDuration> retriesByDuration;

  private DynamicAutoTestRetrySettings(
      boolean enabled, boolean custom, List<ExecutionsByDuration> retriesByDuration) {
    this.enabled = enabled;
    this.custom = custom;
    this.retriesByDuration = Collections.unmodifiableList(new ArrayList<>(retriesByDuration));
  }

  public static DynamicAutoTestRetrySettings create(
      boolean enabled,
      List<Integer> customBuckets,
      List<ExecutionsByDuration> backendRetriesByDuration) {
    if (!enabled) {
      return DEFAULT;
    }
    if (customBuckets == null) {
      return new DynamicAutoTestRetrySettings(true, false, backendRetriesByDuration);
    }

    List<ExecutionsByDuration> retriesByDuration = new ArrayList<>(customBuckets.size());
    for (int i = 0; i < customBuckets.size(); i++) {
      retriesByDuration.add(
          new ExecutionsByDuration(BUCKET_DURATIONS_MILLIS[i], customBuckets.get(i)));
    }
    return new DynamicAutoTestRetrySettings(true, true, retriesByDuration);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isCustom() {
    return custom;
  }

  public int retriesForDuration(long durationMillis) {
    for (ExecutionsByDuration retries : retriesByDuration) {
      if (durationMillis <= retries.getDurationMillis()) {
        return retries.getExecutions();
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
    DynamicAutoTestRetrySettings that = (DynamicAutoTestRetrySettings) o;
    return enabled == that.enabled
        && custom == that.custom
        && Objects.equals(retriesByDuration, that.retriesByDuration);
  }

  @Override
  public int hashCode() {
    return Objects.hash(enabled, custom, retriesByDuration);
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
      serializer.write(settings.retriesByDuration, ExecutionsByDuration.Serializer::serialize);
    }

    public static DynamicAutoTestRetrySettings deserialize(ByteBuffer buffer) {
      byte flags = datadog.trace.civisibility.ipc.serialization.Serializer.readByte(buffer);
      List<ExecutionsByDuration> retriesByDuration =
          datadog.trace.civisibility.ipc.serialization.Serializer.readList(
              buffer, ExecutionsByDuration.Serializer::deserialize);
      return new DynamicAutoTestRetrySettings(
          (flags & ENABLED_FLAG) != 0, (flags & CUSTOM_FLAG) != 0, retriesByDuration);
    }
  }
}
