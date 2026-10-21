package datadog.trace.instrumentation.kafka_common;

import datadog.trace.api.datastreams.DataStreamsTransactionTracker;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public final class Utils {
  private Utils() {} // prevent instantiation

  public static DataStreamsTransactionTracker.TransactionSourceReader
      DSM_TRANSACTION_SOURCE_READER =
          (source, headerName) -> {
            try {
              return new String(((Headers) source).lastHeader(headerName).value());
            } catch (Throwable ignored) {
              return null;
            }
          };

  // this method is used in kafka-clients and kafka-streams instrumentations
  public static long computePayloadSizeBytes(ConsumerRecord<?, ?> val) {
    long headersSize = 0;
    Headers headers = val.headers();
    if (headers != null)
      for (Header h : headers) {
        int valueSize = h.value() == null ? 0 : h.value().length;
        headersSize += valueSize + utf8Length(h.key());
      }
    return headersSize + val.serializedKeySize() + val.serializedValueSize();
  }

  /** Returns exactly {@code s.getBytes(UTF_8).length} without allocating. */
  static int utf8Length(String s) {
    int len = s.length();
    int n = len;
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        continue;
      }
      if (c < 0x800) {
        n += 1;
        continue;
      }
      if (Character.isHighSurrogate(c)
          && i + 1 < len
          && Character.isLowSurrogate(s.charAt(i + 1))) {
        n += 2;
        i++;
        continue;
      }
      if (Character.isSurrogate(c)) {
        continue;
      }
      n += 2;
    }
    return n;
  }
}
