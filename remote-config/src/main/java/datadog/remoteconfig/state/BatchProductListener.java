package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationChangesListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class BatchProductListener implements ProductListener.Batch {

  private final ConfigurationChangesListener.Batch listener;

  public BatchProductListener(ConfigurationChangesListener.Batch listener) {
    this.listener = listener;
  }

  @Override
  public void remove(
      ParsedConfigKey configKey, ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    Map<String, byte[]> configs = new HashMap<>();
    configs.put(configKey.toString(), null);

    listener.accept(configs, pollingRateHinter);
  }

  @Override
  public void commit(ConfigurationChangesListener.PollingRateHinter pollingRateHinter) {}

  @Override
  public Map<ParsedConfigKey, Exception> accept(
      Map<ParsedConfigKey, byte[]> configs, ConfigurationChangesListener.PollingRateHinter hinter) {

    Map<String, byte[]> preparedConfigs =
        configs.entrySet().stream()
            .collect(Collectors.toMap(entry -> entry.getKey().toString(), Map.Entry::getValue));

    listener.accept(preparedConfigs, hinter);

    // We assume that all configs have been successfully applied unless an exception is thrown
    Map<ParsedConfigKey, Exception> configState = new HashMap<>();
    for (ParsedConfigKey key : configs.keySet()) {
      configState.put(key, null);
    }
    return configState;
  }
}
