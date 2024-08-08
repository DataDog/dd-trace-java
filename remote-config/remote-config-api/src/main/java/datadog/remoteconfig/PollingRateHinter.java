package datadog.remoteconfig;

import java.time.Duration;

@FunctionalInterface
public interface PollingRateHinter {
  void suggestPollingRate(Duration duration);
}
