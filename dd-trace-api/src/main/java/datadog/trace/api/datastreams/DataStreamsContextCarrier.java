package datadog.trace.api.datastreams;

import java.util.Map.Entry;
import java.util.Set;

public interface DataStreamsContextCarrier {
  Set<Entry<String, Object>> entries();
  void set(String key, String value);
}
