package datadog.trace.core.propagation;

import datadog.context.propagation.CarrierSetter;
import java.util.Map;

public class MapSetter implements CarrierSetter<Map<String, String>> {
  public static final MapSetter INSTANCE = new MapSetter();

  @Override
  public void set(Map<String, String> carrier, String key, String value) {
    carrier.put(key, value);
  }
}
