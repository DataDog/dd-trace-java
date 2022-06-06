package datadog.trace.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class IntegrationsCollector {

  public static class Holder {
    public static final IntegrationsCollector INSTANCE = new IntegrationsCollector();
  }

  public static IntegrationsCollector get() {
    return Holder.INSTANCE;
  }

  protected static class Integration {
    public Iterable<String> names;
    public boolean enabled;
  }

  private static final Queue<Integration> integrations = new LinkedBlockingQueue<>();

  public synchronized void update(Iterable<String> names, boolean enabled) {
    Integration i = new Integration();
    i.names = names;
    i.enabled = enabled;

    integrations.offer(i);
  }

  public synchronized Map<String, Boolean> drain() {
    if (integrations.isEmpty()) {
      return Collections.emptyMap();
    }

    Map<String, Boolean> map = new LinkedHashMap<>();

    Integration i;
    while ((i = integrations.poll()) != null) {
      boolean enabled = i.enabled;
      for (String name : i.names) {
        map.put(name, enabled);
      }
    }

    return map;
  }
}
