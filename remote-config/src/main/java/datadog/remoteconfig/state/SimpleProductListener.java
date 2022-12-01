package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationChangesListener;
import java.io.IOException;

public class SimpleProductListener implements ProductListener {
  private final ConfigurationChangesListener listener;

  public SimpleProductListener(ConfigurationChangesListener listener) {
    this.listener = listener;
  }

  @Override
  public void accept(
      ParsedConfigKey configKey,
      byte[] content,
      ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    listener.accept(configKey.toString(), content, pollingRateHinter);
  }

  @Override
  public void remove(
      ParsedConfigKey configKey, ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    listener.accept(configKey.toString(), null, pollingRateHinter);
  }

  @Override
  public void commit(ConfigurationChangesListener.PollingRateHinter pollingRateHinter) {}
}
