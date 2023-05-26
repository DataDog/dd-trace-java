package datadog.trace.instrumentation.tomcat7;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParameterCollector {
  public Map<String, List<String>> map;

  public boolean isEmpty() {
    return map == null;
  }

  public void put(String key, String value) {
    if (map == null) {
      map = new HashMap<>();
    }
    List<String> strings = map.get(key);
    if (strings == null) {
      strings = new ArrayList<>();
      map.put(key, strings);
    }
    strings.add(value);
  }

  public void put(String key, String[] values) {
    if (map == null) {
      map = new HashMap<>();
    }
    List<String> strings = map.get(key);
    if (strings == null) {
      strings = new ArrayList<>();
      map.put(key, strings);
    }
    for (String value : values) {
      strings.add(value);
    }
  }
}
