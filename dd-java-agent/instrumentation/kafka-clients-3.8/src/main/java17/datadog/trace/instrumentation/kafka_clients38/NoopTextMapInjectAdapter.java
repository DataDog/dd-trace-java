package datadog.trace.instrumentation.kafka_clients38;

import org.apache.kafka.common.header.Headers;

public class NoopTextMapInjectAdapter implements TextMapInjectAdapterInterface {

  public static final NoopTextMapInjectAdapter NOOP_SETTER = new NoopTextMapInjectAdapter();

  @Override
  public void set(final Headers headers, final String key, final String value) {}

  public void injectTimeInQueue(Headers headers) {}
}
