package datadog.remoteconfig;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public interface ConfigurationChangesTypedListener<T> {
  void accept(
      String configKey,
      @Nullable T configuration, // null to "unapply" the configuration
      ConfigurationChangesListener.PollingRateHinter pollingRateHinter);

  interface Batch<T> {
    void accept(
        Map<String, T> configs, ConfigurationChangesListener.PollingRateHinter pollingRateHinter);
  }

  class Builder {
    static <K> ConfigurationChangesListener useDeserializer(
        ConfigurationDeserializer<K> deserializer, ConfigurationChangesTypedListener<K> listener) {
      return (configKey, content, pollingRateHinter) -> {
        K configuration = null;

        if (content != null) {
          configuration = deserializer.deserialize(content);
          // ensure deserializer return a value.
          if (configuration == null) {
            throw new RuntimeException("Configuration deserializer didn't provide a configuration");
          }
        }
        listener.accept(configKey, configuration, pollingRateHinter);
      };
    }

    static <K> ConfigurationChangesListener.Batch useBatchDeserializer(
        ConfigurationDeserializer<K> deserializer,
        ConfigurationChangesTypedListener.Batch<K> listener) {
      return (configs, pollingRateHinter) -> {
        Map<String, K> parsedConfigs = new HashMap<>();
        configs.forEach(
            (configKey, content) -> {
              if (content != null) {
                try {
                  K configuration = deserializer.deserialize(content);
                  // ensure deserializer return a value.
                  if (configuration == null) {
                    throw new Exception("Deserializer returned NULL value");
                  }
                  parsedConfigs.put(configKey, configuration);
                } catch (Exception ex) {
                  throw new ConfigurationPoller.ReportableException(ex.getMessage(), ex);
                }
              }
            });
        listener.accept(parsedConfigs, pollingRateHinter);
      };
    }
  }
}
