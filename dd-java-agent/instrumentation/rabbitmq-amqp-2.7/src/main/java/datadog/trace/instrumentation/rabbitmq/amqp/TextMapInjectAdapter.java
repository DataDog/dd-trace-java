package datadog.trace.instrumentation.rabbitmq.amqp;

import datadog.context.propagation.CarrierSetter;
import java.util.Map;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class TextMapInjectAdapter implements CarrierSetter<Map<String, Object>> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final Map<String, Object> carrier, final String key, final String value) {
    carrier.put(key, value);
  }
}
