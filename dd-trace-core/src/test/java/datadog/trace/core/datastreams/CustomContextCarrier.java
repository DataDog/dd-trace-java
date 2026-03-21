package datadog.trace.core.datastreams;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

class CustomContextCarrier implements DataStreamsContextCarrier {

  private Map<String, Object> data = new HashMap<>();

  @Override
  public Set<Map.Entry<String, Object>> entries() {
    return data.entrySet();
  }

  @Override
  public void set(String key, String value) {
    data.put(key, value);
  }
}
