package datadog.remoteconfig;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import javax.annotation.Nullable;

public interface ConfigurationChangesListener {
  void accept(
      String configKey,
      @Nullable byte[] content, // null to "unapply" the configuration
      PollingRateHinter pollingRateHinter)
      throws IOException;

  interface Batch {
    void accept(Map<String, byte[]> configs, PollingRateHinter pollingRateHinter);
  }

  interface PollingRateHinter {
    PollingRateHinter NOOP = new PollingHinterNoop();

    void suggestPollingRate(Duration duration);
  }

  class PollingHinterNoop implements PollingRateHinter {
    @Override
    public void suggestPollingRate(Duration duration) {}
  }
}
