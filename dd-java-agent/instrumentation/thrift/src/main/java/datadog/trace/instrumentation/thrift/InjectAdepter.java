package datadog.trace.instrumentation.thrift;

import datadog.context.propagation.CarrierSetter;
import java.util.Map;

public class InjectAdepter implements CarrierSetter<Map<String, String>> {

  public static final InjectAdepter SETTER = new InjectAdepter();

  @Override
  public void set(final Map<String, String> carrier, final String key, final String value) {
    carrier.put(key, value);
  }
}
