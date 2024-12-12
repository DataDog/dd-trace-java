package datadog.remoteconfig;

import java.time.Duration;

public final class PollingHinterNoop implements PollingRateHinter {
  public static final PollingRateHinter NOOP = new PollingHinterNoop();

  private PollingHinterNoop() {}

  @Override
  public void suggestPollingRate(Duration duration) {}
}
