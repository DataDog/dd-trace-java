package datadog.trace.instrumentation.feign;

import datadog.context.propagation.CarrierSetter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class RequestInjectAdapter implements CarrierSetter<Map<String, Collection<String>>> {

  public static final RequestInjectAdapter SETTER = new RequestInjectAdapter();

  @Override
  public void set(
      final Map<String, Collection<String>> carrier, final String key, final String value) {
    Collection<String> values = carrier.get(key);
    if (values == null) {
      values = new ArrayList<>();
      carrier.put(key, values);
    }
    values.add(value);
  }
}
