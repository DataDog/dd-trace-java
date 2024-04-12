package datadog.remoteconfig.state;

import datadog.remoteconfig.PollingRateHinter;
import java.io.IOException;

public interface ProductListener {
  void accept(ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter)
      throws IOException;

  void remove(ConfigKey configKey, PollingRateHinter pollingRateHinter) throws IOException;

  void commit(PollingRateHinter pollingRateHinter);
}
