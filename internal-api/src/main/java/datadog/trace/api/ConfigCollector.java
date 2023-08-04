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

  private volatile Map<String, ConfigSetting> collected = new ConcurrentHashMap<>();

  public static ConfigCollector get() {
    return INSTANCE;
  }

  public void put(String key, Object value, ConfigOrigin origin) {
    Object filteredValue = filterConfigEntry(key, value);
    ConfigSetting setting = new ConfigSetting(key, filteredValue, origin);
    collected.put(key, setting);
  }

  public void putAll(Map<String, Object> keysAndValues, ConfigOrigin origin) {
    // attempt merge+replace to avoid collector seeing partial update
    Map<String, ConfigSetting> merged =
        new ConcurrentHashMap<>(keysAndValues.size() + collected.size());
    for (Map.Entry<String, Object> entry : keysAndValues.entrySet()) {
      Object filteredValue = filterConfigEntry(entry.getKey(), entry.getValue());
      ConfigSetting setting = new ConfigSetting(entry.getKey(), filteredValue, origin);
      merged.put(entry.getKey(), setting);
    }
    while (true) {
      Map<String, ConfigSetting> current = collected;
      current.forEach(merged::putIfAbsent);
      if (COLLECTED_UPDATER.compareAndSet(this, current, merged)) {
        break; // success
      }
      // roll back to original update before next attempt
      merged.keySet().retainAll(keysAndValues.keySet());
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, ConfigSetting> collect() {
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
