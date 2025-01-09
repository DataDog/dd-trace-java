package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import org.apache.kafka.common.header.Headers;

public class TextMapInjectAdapter implements TextMapInjectAdapterInterface {
  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final Headers headers, final String key, final String value) {
    headers.remove(key).add(key, value.getBytes(UTF_8));
  }

  public void injectTimeInQueue(Headers headers) {
    ByteBuffer buf = ByteBuffer.allocate(8);
    buf.putLong(System.currentTimeMillis());
    headers.add(KafkaDecorator.KAFKA_PRODUCED_KEY, buf.array());
  }
}
