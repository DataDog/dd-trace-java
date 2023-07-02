package datadog.trace.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Collects system properties and environment variables set by the user and used by the tracer. Puts
 * to this map will happen in Config and ConfigProvider classes, which can run concurrently with
 * consumers. So this is based on a ConcurrentHashMap to deal with it.
 */
public class ConfigCollector {

  private static final Set<String> CONFIG_FILTER_LIST =
      new HashSet<>(
          Arrays.asList("DD_API_KEY", "dd.api-key", "dd.profiling.api-key", "dd.profiling.apikey"));

  private static final ConfigCollector INSTANCE = new ConfigCollector();

  private static final AtomicReferenceFieldUpdater<ConfigCollector, Map> COLLECTED_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(ConfigCollector.class, Map.class, "collected");

  private volatile Map<String, Object> collected = new ConcurrentHashMap<>();

  public static ConfigCollector get() {
    return INSTANCE;
  }

  public void put(String key, Object value) {
    collected.put(key, filterConfigEntry(key, value));
  }

  public void putAll(Map<String, Object> keysAndValues) {
    // attempt merge+replace to avoid collector seeing partial update
    Map<String, Object> merged = new ConcurrentHashMap<>(keysAndValues);
    merged.replaceAll(ConfigCollector::filterConfigEntry);
    while (true) {
      Map<String, Object> current = collected;
      current.forEach(merged::putIfAbsent);
      if (COLLECTED_UPDATER.compareAndSet(this, current, merged)) {
        break; // success
      }
      // roll back to original update before next attempt
      merged.keySet().retainAll(keysAndValues.keySet());
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> collect() {
    if (!collected.isEmpty()) {
      return COLLECTED_UPDATER.getAndSet(this, new ConcurrentHashMap<>());
    } else {
      return Collections.emptyMap();
    }
  }

  private static Object filterConfigEntry(String key, Object value) {
    return CONFIG_FILTER_LIST.contains(key) ? "<hidden>" : value;
  }
}
