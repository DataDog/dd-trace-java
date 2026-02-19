package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import datadog.trace.util.HashingUtils;

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
    return HashingUtils.hash(enabled, executionsByDuration, faultySessionThreshold);
  }

  public static final class Serializer {
    public static void serialize(
        datadog.trace.civisibility.ipc.serialization.Serializer serializer,
        EarlyFlakeDetectionSettings settings) {
      if (!settings.enabled) {
        serializer.write((byte) 0);
        return;
      }
      serializer.write((byte) 1);
      serializer.write(settings.faultySessionThreshold);
      serializer.write(settings.executionsByDuration, ExecutionsByDuration.Serializer::serialize);
    }

    public static EarlyFlakeDetectionSettings deserialize(ByteBuffer buf) {
      boolean enabled = datadog.trace.civisibility.ipc.serialization.Serializer.readByte(buf) != 0;
      if (!enabled) {
        return EarlyFlakeDetectionSettings.DEFAULT;
      }

      int faultySessionThreshold =
          datadog.trace.civisibility.ipc.serialization.Serializer.readInt(buf);
      List<ExecutionsByDuration> executionsByDuration =
          datadog.trace.civisibility.ipc.serialization.Serializer.readList(
              buf, ExecutionsByDuration.Serializer::deserialize);
      return new EarlyFlakeDetectionSettings(enabled, executionsByDuration, faultySessionThreshold);
    }
  }

  public static final class JsonAdapter {
    public static final JsonAdapter INSTANCE = new JsonAdapter();

    @FromJson
    public EarlyFlakeDetectionSettings fromJson(Map<String, Object> json) {
      if (json == null) {
        return EarlyFlakeDetectionSettings.DEFAULT;
      }

      Boolean enabled = (Boolean) json.get("enabled");
      Double faultySessionThreshold = (Double) json.get("faulty_session_threshold");

      List<ExecutionsByDuration> executionsByDuration;
      Map<String, Double> slowTestRetries = (Map<String, Double>) json.get("slow_test_retries");
      if (slowTestRetries != null) {
        executionsByDuration = new ArrayList<>(slowTestRetries.size());
        for (Map.Entry<String, Double> e : slowTestRetries.entrySet()) {
          long durationMillis = parseDuration(e.getKey());
          int retries = e.getValue().intValue();
          executionsByDuration.add(new ExecutionsByDuration(durationMillis, retries));
        }
        executionsByDuration.sort(Comparator.comparingLong(r -> r.durationMillis));
      } else {
        executionsByDuration = Collections.emptyList();
      }

      return new EarlyFlakeDetectionSettings(
          enabled != null ? enabled : false,
          executionsByDuration,
          faultySessionThreshold != null ? faultySessionThreshold.intValue() : -1);
    }

    private static long parseDuration(String duration) {
      char lastCharacter = duration.charAt(duration.length() - 1);
      int numericValue = Integer.parseInt(duration.substring(0, duration.length() - 1));
      TimeUnit timeUnit;
      switch (lastCharacter) {
        case 's':
          timeUnit = TimeUnit.SECONDS;
          break;
        case 'm':
          timeUnit = TimeUnit.MINUTES;
          break;
        case 'h':
          timeUnit = TimeUnit.HOURS;
          break;
        default:
          throw new IllegalArgumentException("Unexpected duration unit: " + lastCharacter);
      }
      return timeUnit.toMillis(numericValue);
    }
  }
}
