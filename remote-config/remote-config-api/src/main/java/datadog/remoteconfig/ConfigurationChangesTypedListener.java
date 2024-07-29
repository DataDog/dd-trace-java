package datadog.remoteconfig;

import javax.annotation.Nullable;

/** This interface describes a configuration strongly-typed value change. */
@FunctionalInterface
public interface ConfigurationChangesTypedListener<T> {
  /**
   * Notifies a new configuration value change.
   *
   * @param configKey The configuration key that changed.
   * @param configuration The new configuration value, might be {@code null} to "unapply" the
   *     configuration.
   * @param pollingRateHinter The callback to hint about the expected polling rate.
   */
  void accept(
      String configKey,
      @Nullable T configuration, // null to "unapply" the configuration
      PollingRateHinter pollingRateHinter);

  class Builder {
    /**
     * Creates a {@link ConfigurationChangesListener} for a strongly-typed configuration.
     *
     * @param deserializer The configuration value deserializer.
     * @param listener The strongly-typed listener to delegate the notification.
     * @return The resulting {@link ConfigurationChangesListener} instance.
     * @param <K> The type of the configuration value.
     */
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
