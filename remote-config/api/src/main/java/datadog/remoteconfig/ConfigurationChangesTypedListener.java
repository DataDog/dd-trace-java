package datadog.remoteconfig;

import javax.annotation.Nullable;

public interface ConfigurationChangesTypedListener<T> {
  void accept(
      String configKey,
      @Nullable T configuration, // null to "unapply" the configuration
      ConfigurationChangesListener.PollingRateHinter pollingRateHinter);

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
  }
}
