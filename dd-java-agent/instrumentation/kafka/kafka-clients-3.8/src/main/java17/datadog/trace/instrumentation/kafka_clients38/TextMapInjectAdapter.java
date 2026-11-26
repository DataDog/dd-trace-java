package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.datastreams.BinaryCarrierSetter;
import java.nio.ByteBuffer;
import org.apache.kafka.common.header.Headers;

public class TextMapInjectAdapter
    implements TextMapInjectAdapterInterface, BinaryCarrierSetter<Headers> {
  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  private TextMapInjectAdapter() {}

  @Override
  public void set(final Headers headers, final String key, final String value) {
    // Codecs can write the same key, so replace it, but probe first: remove allocates an iterator,
    // lastHeader doesn't, and the key is usually not there yet
    if (headers.lastHeader(key) != null) {
      headers.remove(key);
    }
    headers.add(key, value.getBytes(UTF_8));
  }

  @Override
  public void setBytes(final Headers headers, final String key, final byte[] value) {
    headers.remove(key).add(key, value);
  }

  public void injectTimeInQueue(Headers headers) {
    ByteBuffer buf = ByteBuffer.allocate(8);
    buf.putLong(System.currentTimeMillis());
    headers.add(KafkaDecorator.KAFKA_PRODUCED_KEY, buf.array());
  }
}
