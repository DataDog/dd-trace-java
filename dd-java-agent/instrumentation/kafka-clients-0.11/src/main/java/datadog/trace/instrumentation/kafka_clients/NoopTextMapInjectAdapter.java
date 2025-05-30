package datadog.trace.instrumentation.kafka_clients;

import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.kafka.common.header.Headers;

@ParametersAreNonnullByDefault
public class NoopTextMapInjectAdapter implements TextMapInjectAdapterInterface {
  public static final NoopTextMapInjectAdapter NOOP_SETTER = new NoopTextMapInjectAdapter();

  @Override
  public void set(final Headers headers, final String key, final String value) {}

  public void injectTimeInQueue(Headers headers) {}
}
