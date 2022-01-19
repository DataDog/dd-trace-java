package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;

public class TextMapInjectAdapter implements AgentPropagation.Setter<Headers> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  public static final String KAFKA_PRODUCED_KEY = "x_datadog_kafka_produced";

  @Override
  public void set(final Headers headers, final String key, final String value) {
    headers.remove(key).add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  public void injectTimeInQueue(Headers headers) {
    ByteBuffer buf = ByteBuffer.allocate(8);
    buf.putLong(System.currentTimeMillis());
    headers.add(KAFKA_PRODUCED_KEY, buf.array());
  }
}
