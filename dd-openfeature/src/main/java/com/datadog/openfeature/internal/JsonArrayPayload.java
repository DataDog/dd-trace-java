package com.datadog.openfeature.internal;

import datadog.trace.util.ChunkedBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * An event payload being built: a JSON document whose events form an array between a fixed prefix
 * and suffix, such as {@code {"context":{...},"exposures":[} and {@code ]}}. The events are written
 * into a reused {@link ChunkedBuffer}, so a payload of several MiB is never one large array, and
 * the size limit covers the whole document. Not thread-safe.
 */
public final class JsonArrayPayload {
  private final ByteBuffer prefix;
  private final ByteBuffer suffix;
  private final int capacity;
  private ChunkedBuffer events;
  private int count;

  /**
   * Creates an empty payload.
   *
   * @param prefix the document up to and including the array's {@code [}.
   * @param suffix the document from the array's {@code ]}.
   * @param sizeLimitBytes the most bytes the whole document may take.
   */
  public JsonArrayPayload(final byte[] prefix, final byte[] suffix, final int sizeLimitBytes) {
    this.prefix = ByteBuffer.wrap(prefix);
    this.suffix = ByteBuffer.wrap(suffix);
    this.capacity = sizeLimitBytes - prefix.length - suffix.length;
    this.events = new ChunkedBuffer(capacity);
  }

  /**
   * Appends an event, after a separator when the array already has events.
   *
   * @param writer writes the event as one JSON value.
   * @return whether it fit; if not, the payload is unchanged.
   * @throws RuntimeException from the writer; the payload is unchanged.
   */
  public boolean append(final JsonWriting.Writer writer) {
    if (count > 0) {
      events.put((byte) ',');
    }
    // On overflow or failure, write() rewinds to the previous event, separator included.
    if (!JsonWriting.write(events, writer)) {
      return false;
    }
    count++;
    return true;
  }

  /**
   * Gets the number of events in the payload.
   *
   * @return the event count.
   */
  public int count() {
    return count;
  }

  /**
   * Gets the whole document to post, as views over the payload's buffers. They stay valid until
   * {@link #reset} or {@link #discard}.
   *
   * @return the prefix, the events and the suffix, in order.
   */
  public List<ByteBuffer> body() {
    final List<ByteBuffer> chunks = events.completedChunks();
    final List<ByteBuffer> body = new ArrayList<>(chunks.size() + 2);
    body.add(prefix.duplicate());
    body.addAll(chunks);
    body.add(suffix.duplicate());
    return body;
  }

  /** Empties the payload for reuse, once its body has been posted. */
  public void reset() {
    events.reset();
    count = 0;
  }

  /**
   * Empties the payload into new buffers, after a post of its body failed: a request that timed out
   * can still be reading the old ones.
   */
  public void discard() {
    events = new ChunkedBuffer(capacity);
    count = 0;
  }
}
