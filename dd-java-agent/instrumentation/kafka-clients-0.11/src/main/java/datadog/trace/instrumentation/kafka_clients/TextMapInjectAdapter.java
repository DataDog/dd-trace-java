package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_PRODUCED_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.kafka.common.header.Headers;

@ParametersAreNonnullByDefault
public class TextMapInjectAdapter implements TextMapInjectAdapterInterface {
  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final Headers headers, final String key, final String value) {
    headers.remove(key).add(key, value.getBytes(UTF_8));
  }

  public void injectTimeInQueue(Headers headers) {
    ByteBuffer buf = ByteBuffer.allocate(8);
    buf.putLong(System.currentTimeMillis());
    headers.add(KAFKA_PRODUCED_KEY, buf.array());
  }
}
