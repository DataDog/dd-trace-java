package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class EarlyFlakeDetectionSettingsJsonAdapter {
  public static final EarlyFlakeDetectionSettingsJsonAdapter INSTANCE =
      new EarlyFlakeDetectionSettingsJsonAdapter();

  @FromJson
  public EarlyFlakeDetectionSettings fromJson(Map<String, Object> json) {
    if (json == null) {
      return EarlyFlakeDetectionSettings.DEFAULT;
    }

    Boolean enabled = (Boolean) json.get("enabled");
    Double faultySessionThreshold = (Double) json.get("faulty_session_threshold");

    List<EarlyFlakeDetectionSettings.ExecutionsByDuration> executionsByDuration;
    Map<String, Double> slowTestRetries = (Map<String, Double>) json.get("slow_test_retries");
    if (slowTestRetries != null) {
      executionsByDuration = new ArrayList<>(slowTestRetries.size());
      for (Map.Entry<String, Double> e : slowTestRetries.entrySet()) {
        long durationMillis = parseDuration(e.getKey());
        int retries = e.getValue().intValue();
        executionsByDuration.add(
            new EarlyFlakeDetectionSettings.ExecutionsByDuration(durationMillis, retries));
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
