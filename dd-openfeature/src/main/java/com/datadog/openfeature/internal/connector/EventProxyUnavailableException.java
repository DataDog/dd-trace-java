package com.datadog.openfeature.internal.connector;

import java.io.IOException;

/**
 * Thrown by an event proxy {@link EventTransport} when the proxy definitively rejects the payload
 * as unavailable, so the SDK can fall back to direct intake.
 */
public final class EventProxyUnavailableException extends IOException {
  public EventProxyUnavailableException(final String message) {
    super(message);
  }
}
