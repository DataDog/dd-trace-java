package datadog.trace.instrumentation.axis2;

import datadog.context.propagation.CarrierSetter;
import java.util.Map;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class TextMapInjectAdapter implements CarrierSetter<Map<String, Object>> {
  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(Map<String, Object> carrier, String key, String value) {
    carrier.put(key, value);
  }
}
