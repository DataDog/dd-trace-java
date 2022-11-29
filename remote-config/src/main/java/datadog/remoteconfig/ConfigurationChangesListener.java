package datadog.remoteconfig;

import java.io.IOException;
import java.time.Duration;
import javax.annotation.Nullable;

public interface ConfigurationChangesListener {
  void accept(
      String configKey,
      @Nullable byte[] content, // null to "unapply" the configuration
      PollingRateHinter pollingRateHinter)
      throws IOException;

  interface PollingRateHinter {
    PollingRateHinter NOOP = new PollingHinterNoop();

    void suggestPollingRate(Duration duration);
  }

  class PollingHinterNoop implements PollingRateHinter {
    @Override
    public void suggestPollingRate(Duration duration) {}
  }
}
