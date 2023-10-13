package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationChangesListener;
import java.io.IOException;

public interface ProductListener {
  void accept(
      ParsedConfigKey configKey,
      byte[] content,
      ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException;

  void remove(
      ParsedConfigKey configKey, ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException;

  void commit(ConfigurationChangesListener.PollingRateHinter pollingRateHinter);
}
