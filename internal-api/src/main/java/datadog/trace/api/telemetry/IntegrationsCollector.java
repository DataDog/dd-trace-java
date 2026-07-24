package datadog.trace.api.telemetry;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class IntegrationsCollector {

  private static final IntegrationsCollector INSTANCE = new IntegrationsCollector();
  private final Queue<Integration> integrations = new LinkedBlockingQueue<>();

  private IntegrationsCollector() {}

  public static IntegrationsCollector get() {
    return INSTANCE;
  }

  // Claude: SpotBugs USO_UNSAFE_METHOD_SYNCHRONIZATION: should be reviewed by team.
  // This is a singleton exposed via the static get()/INSTANCE accessor, so any code holding the
  // instance synchronizes on the same monitor that this telemetry path uses. The backing queue is
  // already a LinkedBlockingQueue, so the method-level lock looks redundant and could be dropped or
  // replaced with a private lock.
  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification = "Singleton exposed via static accessor; review whether monitor is needed")
  public synchronized void update(Iterable<String> names, boolean enabled) {
    Integration i = new Integration();
    i.names = names;
    i.enabled = enabled;

    integrations.offer(i);
  }

  // Claude: SpotBugs USO_UNSAFE_METHOD_SYNCHRONIZATION: should be reviewed by team.
  // This is a singleton exposed via the static get()/INSTANCE accessor, so any code holding the
  // instance synchronizes on the same monitor that this telemetry path uses. The backing queue is
  // already a LinkedBlockingQueue, so the method-level lock looks redundant and could be dropped or
  // replaced with a private lock.
  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification = "Singleton exposed via static accessor; review whether monitor is needed")
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

  private static class Integration {
    public Iterable<String> names;
    public boolean enabled;
  }
}
