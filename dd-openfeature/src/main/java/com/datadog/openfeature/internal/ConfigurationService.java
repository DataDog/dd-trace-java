package com.datadog.openfeature.internal;

import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import java.util.function.Consumer;

/** Delivers flag configurations to the runtime. */
public interface ConfigurationService extends AutoCloseable {
  /**
   * Starts delivering configurations. Implementations may deliver the first configuration before
   * returning.
   *
   * @param listener the listener receiving configurations, and {@code null} when the configuration
   *     is removed.
   */
  void start(Consumer<ServerConfiguration> listener);

  @Override
  void close();
}
