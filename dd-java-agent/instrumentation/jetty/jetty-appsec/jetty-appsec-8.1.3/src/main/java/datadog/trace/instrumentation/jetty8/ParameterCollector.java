package datadog.trace.instrumentation.jetty8;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface ParameterCollector {
  boolean isEmpty();

  // Takes Object to accommodate both Jetty 8.x (MultiMap.add(Object,Object)) and
  // Jetty 9.x (MultiMap.add(String,Object)) bytecode call sites.
  void put(Object key, Object value);

  Map<String, List<String>> getMap();

  class ParameterCollectorNoop implements ParameterCollector {
    public static final ParameterCollector INSTANCE = new ParameterCollectorNoop();

    private ParameterCollectorNoop() {}

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public void put(Object key, Object value) {}

    @Override
    public Map<String, List<String>> getMap() {
      return Collections.emptyMap();
    }
  }

  class ParameterCollectorImpl implements ParameterCollector {
    public Map<String, List<String>> map;

    public boolean isEmpty() {
      return map == null;
    }

    public void put(Object key, Object value) {
      if (!(key instanceof String) || !(value instanceof String)) {
        return;
      }
      if (map == null) {
        map = new HashMap<>();
      }
      List<String> strings = map.get(key);
      if (strings == null) {
        strings = new ArrayList<>();
        map.put((String) key, strings);
      }
      strings.add((String) value);
    }

    @Override
    public Map<String, List<String>> getMap() {
      return map;
    }
  }
}
