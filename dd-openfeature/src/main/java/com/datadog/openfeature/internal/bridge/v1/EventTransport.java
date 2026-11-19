package com.datadog.openfeature.internal.bridge.v1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

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

  /**
   * Posts a JSON payload held in chunks, so that a payload of several MiB needs no large contiguous
   * array. This default joins the chunks for a transport that only posts arrays.
   *
   * @param route the event platform route, like {@code exposures} or {@code flagevaluation}.
   * @param json the UTF-8 JSON payload, in order; each buffer is read from its position to its
   *     limit, and left unchanged. The caller may reuse the buffers once this returns normally, but
   *     not after it throws: a request that timed out can still be reading them.
   * @throws IOException if the payload could not be delivered.
   */
  default void post(final String route, final List<ByteBuffer> json) throws IOException {
    int size = 0;
    for (final ByteBuffer chunk : json) {
      size += chunk.remaining();
    }
    final byte[] joined = new byte[size];
    int offset = 0;
    for (final ByteBuffer chunk : json) {
      final int length = chunk.remaining();
      chunk.duplicate().get(joined, offset, length);
      offset += length;
    }
    post(route, joined);
  }
}
