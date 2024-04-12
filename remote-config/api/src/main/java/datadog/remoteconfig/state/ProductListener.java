package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationChangesListener;
import java.io.IOException;

public interface ProductListener {
  void accept(
      ConfigKey configKey,
      byte[] content,
      ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException;

  void remove(ConfigKey configKey, ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException;

  void commit(ConfigurationChangesListener.PollingRateHinter pollingRateHinter);
}
