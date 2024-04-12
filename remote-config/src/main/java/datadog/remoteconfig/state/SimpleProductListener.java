package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.PollingRateHinter;
import java.io.IOException;

public class SimpleProductListener implements ProductListener {
  private final ConfigurationChangesListener listener;

  public SimpleProductListener(ConfigurationChangesListener listener) {
    this.listener = listener;
  }

  @Override
  public void accept(ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter)
      throws IOException {
    listener.accept(configKey.toString(), content, pollingRateHinter);
  }

  @Override
  public void remove(ConfigKey configKey, PollingRateHinter pollingRateHinter) throws IOException {
    listener.accept(configKey.toString(), null, pollingRateHinter);
  }

  @Override
  public void commit(PollingRateHinter pollingRateHinter) {}
}
