package com.datadog.openfeature.internal.bridge.v1;

/** A source of application shutdown notifications. */
public interface ShutdownSource {
  /**
   * Subscribes to the application shutdown.
   *
   * @param listener the listener run at shutdown, typically flushing the pending events.
   * @return the handle to close to unsubscribe.
   */
  AutoCloseable subscribe(Runnable listener);
}
