package datadog.remoteconfig;

import java.time.Duration;

@FunctionalInterface
public interface PollingRateHinter {
  PollingRateHinter NOOP = new PollingHinterNoop();

  void suggestPollingRate(Duration duration);

  class PollingHinterNoop implements PollingRateHinter {
    @Override
    public void suggestPollingRate(Duration duration) {}
  }
}
