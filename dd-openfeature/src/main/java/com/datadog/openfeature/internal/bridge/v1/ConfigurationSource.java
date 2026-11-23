package com.datadog.openfeature.internal.bridge.v1;

import java.util.function.Consumer;

/** A source of Universal Flag Configuration (UFC) documents. */
public interface ConfigurationSource {
  /**
   * Subscribes to configuration updates.
   *
   * @param listener the listener receiving raw UFC JSON documents, or {@code null} when the
   *     configuration is removed. It throws a {@link RuntimeException} to reject a malformed
   *     document, which the source reports as not applied.
   * @return the handle to close to unsubscribe.
   */
  AutoCloseable subscribe(Consumer<byte[]> listener);
}
