package datadog.remoteconfig;

import datadog.remoteconfig.state.ProductListener;
import java.util.List;

public interface ConfigurationPoller {
  void addListener(Product product, ProductListener listener);

  <T> void addListener(
      Product product,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesTypedListener<T> listener);

  void addListener(Product product, String configKey, ProductListener listener);

  <T> void addListener(
      Product product,
      String configKey,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesTypedListener<T> listener);

  void addListener(
      Product product, ConfigurationChangesListener mapConfigurationChangesTypedListener);

  void removeListeners(Product product);

  default void addUserConfigError(String message, String level, List<String> tags) {
    // used in default implementation
  }

  void addConfigurationEndListener(ConfigurationEndListener listener);

  void removeConfigurationEndListener(ConfigurationEndListener listener);

  void addCapabilities(long flags);

  void removeCapabilities(long flags);

  void start();

  void stop();
}
