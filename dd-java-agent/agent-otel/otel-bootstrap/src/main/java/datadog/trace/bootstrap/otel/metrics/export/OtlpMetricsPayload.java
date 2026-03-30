package datadog.trace.bootstrap.otel.metrics.export;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** OTLP metrics payload consisting of a sequence of chunked byte-arrays. */
public final class OtlpMetricsPayload {
  static final OtlpMetricsPayload EMPTY = new OtlpMetricsPayload(new ArrayDeque<>(), 0);

  private final Deque<byte[]> chunks;
  private final int length;

  OtlpMetricsPayload(Deque<byte[]> chunks, int length) {
    this.chunks = chunks;
    this.length = length;
  }

  /** Drains the chunked payload to the given consumer. */
  public void drain(Consumer<byte[]> consumer) {
    byte[] chunk;
    while ((chunk = chunks.pollFirst()) != null) {
      consumer.accept(chunk);
    }
  }

  /** Returns the total length of the chunked payload. */
  public int getLength() {
    return length;
  }
}
