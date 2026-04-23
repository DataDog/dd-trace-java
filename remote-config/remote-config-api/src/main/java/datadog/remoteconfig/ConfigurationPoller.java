package datadog.remoteconfig;

import datadog.remoteconfig.state.ProductListener;

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

  void addListener(Product product, ConfigurationChangesListener configurationChangesListener);

  void removeListeners(Product product);

  void addConfigurationEndListener(ConfigurationEndListener listener);

  void removeConfigurationEndListener(ConfigurationEndListener listener);

  void addCapabilities(long flags);

  void removeCapabilities(long flags);

  void start();

  void stop();

  /**
   * Registers a listener that is called when a non-retryable HTTP error (e.g. 401, 403) is
   * received from the RC endpoint. The default implementation is a no-op.
   */
  default void addNonRetryableErrorListener(NonRetryableErrorListener listener) {}

  default void removeNonRetryableErrorListener(NonRetryableErrorListener listener) {}

  @FunctionalInterface
  interface NonRetryableErrorListener {
    void onNonRetryableError(int httpStatus, String message);
  }
}
