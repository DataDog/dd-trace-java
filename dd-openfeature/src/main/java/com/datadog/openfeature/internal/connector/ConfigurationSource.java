package com.datadog.openfeature.internal.connector;

import java.util.function.Consumer;

/** A source of Universal Flag Configuration (UFC) documents. */
public interface ConfigurationSource {
  /**
   * Subscribes to configuration updates.
   *
   * @param listener the listener receiving raw UFC JSON documents, or {@code null} when the
   *     configuration is removed.
   * @return the handle to close to unsubscribe.
   */
  AutoCloseable subscribe(Consumer<byte[]> listener);
}
