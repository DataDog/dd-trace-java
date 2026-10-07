package com.datadog.openfeature.internal.connector;

import java.io.IOException;

/** Delivers serialized product events to the Datadog event platform. */
@FunctionalInterface
public interface EventTransport {
  /**
   * Posts a JSON payload.
   *
   * @param route the event platform route, like {@code exposures} or {@code flagevaluation}.
   * @param json the UTF-8 JSON payload.
   * @throws IOException if the payload could not be delivered.
   */
  void post(String route, byte[] json) throws IOException;
}
