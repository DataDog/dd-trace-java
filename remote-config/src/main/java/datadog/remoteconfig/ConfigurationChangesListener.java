package datadog.remoteconfig;

import java.time.Duration;
import javax.annotation.Nullable;

public interface ConfigurationChangesListener<T> {
  void accept(
      String configKey,
      @Nullable T configuration, // null to "unapply" the configuration
      PollingRateHinter pollingRateHinter);

  interface PollingRateHinter {
    PollingRateHinter NOOP = new PollingHinterNoop();

    void suggestPollingRate(Duration duration);
  }

  class PollingHinterNoop implements PollingRateHinter {
    @Override
    public void suggestPollingRate(Duration duration) {}
  }
}
