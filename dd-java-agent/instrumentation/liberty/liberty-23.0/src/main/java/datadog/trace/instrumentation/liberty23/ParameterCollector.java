package datadog.trace.instrumentation.liberty23;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public interface ParameterCollector {

  boolean isEmpty();

  void put(String key, String[] value);

  Map<String, String[]> getMap();

  class ParameterCollectorNoop implements ParameterCollector {
    public static final ParameterCollector INSTANCE = new ParameterCollectorNoop();

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public void put(String key, String[] value) {}

    @Override
    public Map<String, String[]> getMap() {
      return Collections.emptyMap();
    }
  }

  class ParameterCollectorImpl implements ParameterCollector {
    public Map<String, String[]> map;

    @Override
    public boolean isEmpty() {
      return map == null;
    }

    @Override
    public void put(String key, String[] value) {
      if (map == null) {
        map = new HashMap<>();
      }
      map.put(key, value);
    }

    @Override
    public Map<String, String[]> getMap() {
      return map;
    }
  }
}
