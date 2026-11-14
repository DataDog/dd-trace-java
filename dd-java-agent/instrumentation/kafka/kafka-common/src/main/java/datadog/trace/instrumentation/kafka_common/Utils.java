package datadog.trace.instrumentation.kafka_common;

import datadog.trace.api.datastreams.DataStreamsTransactionTracker;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public final class Utils {
  private Utils() {} // prevent instantiation

  public static DataStreamsTransactionTracker.TransactionSourceReader
      DSM_TRANSACTION_SOURCE_READER =
          (source, headerName) -> {
            try {
              return new String(
                  ((Headers) source).lastHeader(headerName).value(), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
              return null;
            }
          };

  /**
   * Reads the produce time a producer wrote to its header, as 8 big-endian bytes.
   *
   * @throws IllegalArgumentException if {@code bytes} isn't 8 bytes long
   */
  public static long readProducedTime(byte[] bytes) {
    if (bytes.length != Long.BYTES) {
      throw new IllegalArgumentException("Expected 8 bytes, got " + bytes.length);
    }
    long time = 0;
    for (byte b : bytes) {
      time = (time << 8) | (b & 0xff);
    }
    return time;
  }

  /**
   * Whether {@code headers} holds any header. {@link Headers#toArray()} would copy the headers of
   * every record that has some, just to check.
   */
  public static boolean hasHeaders(Headers headers) {
    return headers.iterator().hasNext();
  }

  // this method is used in kafka-clients and kafka-streams instrumentations
  public static long computePayloadSizeBytes(ConsumerRecord<?, ?> val) {
    long headersSize = 0;
    Headers headers = val.headers();
    if (headers != null)
      for (Header h : headers) {
        int valueSize = h.value() == null ? 0 : h.value().length;
        headersSize += valueSize + h.key().getBytes(StandardCharsets.UTF_8).length;
      }
    return headersSize + val.serializedKeySize() + val.serializedValueSize();
  }
}
