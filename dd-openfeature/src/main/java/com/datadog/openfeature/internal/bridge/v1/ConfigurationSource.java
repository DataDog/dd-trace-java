package com.datadog.openfeature.internal.bridge.v1;

import java.util.Set;
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

  /**
   * Subscribes with the evaluator's optional UFC capabilities. Older agents remain conservative.
   *
   * @param listener the configuration listener.
   * @param capabilities features supported by evaluation and exposure reporting.
   * @return the subscription handle.
   */
  default AutoCloseable subscribe(Consumer<byte[]> listener, Set<String> capabilities) {
    return subscribe(listener);
  }
}
