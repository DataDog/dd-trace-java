package datadog.trace.instrumentation.kafka_clients38;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;

public class TextMapInjectAdapter implements TextMapInjectAdapterInterface {
  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final Headers headers, final String key, final String value) {
    headers.remove(key).add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void set(Headers headers, String key, byte[] value) {
    headers.remove(key).add(key, value);
  }

  public void injectTimeInQueue(Headers headers) {
    ByteBuffer buf = ByteBuffer.allocate(8);
    buf.putLong(System.currentTimeMillis());
    headers.add(KafkaDecorator.KAFKA_PRODUCED_KEY, buf.array());
  }
}
