package datadog.trace.instrumentation.kafka_common;

import datadog.trace.api.datastreams.DataStreamsTransactionTracker;
import java.util.AbstractMap;
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
        byte[] value = h.value();
        int valueSize = value == null ? 0 : value.length;
        headersSize += valueSize + keyUtf8Length(h.key());
      }
    return headersSize + val.serializedKeySize() + val.serializedValueSize();
  }

  private static final int KEY_LENGTH_CACHE_SIZE = 128; // power of two
  private static final int MAX_CACHED_KEY_LENGTH = 64; // chars; longer keys are not cached

  /** Direct-mapped, lock-free cache of header key to UTF-8 length; races only cost a miss. */
  @SuppressWarnings("unchecked")
  private static final AbstractMap.SimpleImmutableEntry<String, Integer>[] KEY_UTF8_LENGTHS =
      new AbstractMap.SimpleImmutableEntry[KEY_LENGTH_CACHE_SIZE];

  /** Returns exactly {@code key.getBytes(UTF_8).length}, cached for short keys. */
  static int keyUtf8Length(String key) {
    if (key.length() > MAX_CACHED_KEY_LENGTH) {
      return utf8Length(key);
    }
    int h = key.hashCode();
    int i = (h ^ (h >>> 16)) & (KEY_LENGTH_CACHE_SIZE - 1);
    AbstractMap.SimpleImmutableEntry<String, Integer> e = KEY_UTF8_LENGTHS[i];
    if (e != null && key.equals(e.getKey())) {
      return e.getValue();
    }
    int n = utf8Length(key);
    KEY_UTF8_LENGTHS[i] = new AbstractMap.SimpleImmutableEntry<>(key, n);
    return n;
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
