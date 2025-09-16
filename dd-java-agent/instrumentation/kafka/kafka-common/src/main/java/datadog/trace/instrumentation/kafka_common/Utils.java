package datadog.trace.instrumentation.kafka_common;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public final class Utils {
  private Utils() {} // prevent instantiation

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
