package datadog.trace.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class IntegrationsCollector {

  public static class Holder {
    public static final IntegrationsCollector INSTANCE = new IntegrationsCollector();
  }

  public static IntegrationsCollector get() {
    return Holder.INSTANCE;
  }

  private static final Map<String, Boolean> integrations = new LinkedHashMap<>();

  public synchronized void update(Iterable<String> names, boolean enabled) {
    for (String name : names) {
      integrations.put(name, enabled);
    }
  }

  public synchronized Map<String, Boolean> drain() {
    if (integrations.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, Boolean> map = new LinkedHashMap<>(integrations);
    integrations.clear();
    return map;
  }
}
