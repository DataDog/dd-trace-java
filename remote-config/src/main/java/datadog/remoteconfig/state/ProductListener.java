package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.ConfigurationPoller;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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

  interface Batch {
    Map<ParsedConfigKey, Exception> accept(
        Map<ParsedConfigKey, byte[]> configs,
        ConfigurationChangesListener.PollingRateHinter hinter);

    void remove(
        ParsedConfigKey configKey, ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
        throws IOException;

    void commit(ConfigurationChangesListener.PollingRateHinter pollingRateHinter);
  }

  class WrapperProductListener implements Batch {
    private final ProductListener listener;

    public WrapperProductListener(ProductListener listener) {
      this.listener = listener;
    }

    @Override
    public Map<ParsedConfigKey, Exception> accept(
        Map<ParsedConfigKey, byte[]> configs, ConfigurationChangesListener.PollingRateHinter hinter)
        throws ConfigurationPoller.ReportableException {
      Map<ParsedConfigKey, Exception> state = new HashMap<>();
      configs.forEach(
          (configKey, content) -> {
            try {
              listener.accept(configKey, content, hinter);
              state.put(configKey, null);
            } catch (Exception e) {
              state.put(configKey, e);
            }
          });
      return state;
    }

    @Override
    public void remove(
        ParsedConfigKey configKey, ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
        throws IOException {
      listener.remove(configKey, pollingRateHinter);
    }

    @Override
    public void commit(ConfigurationChangesListener.PollingRateHinter pollingRateHinter) {
      listener.commit(pollingRateHinter);
    }
  }
}
