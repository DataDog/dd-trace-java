package datadog.trace.api;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Collects system properties and environment variables set by the user and used by the tracer. Puts
 * to this map will happen in Config and ConfigProvider classes, which can run concurrently with
 * consumers. So this is based on a ConcurrentHashMap to deal with it.
 */
public class ConfigCollector {
  private static final ConfigCollector INSTANCE = new ConfigCollector();

  private static final AtomicReferenceFieldUpdater<ConfigCollector, Map> COLLECTED_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(ConfigCollector.class, Map.class, "collected");

  private volatile Map<String, ConfigSetting> collected = new ConcurrentHashMap<>();

  public static ConfigCollector get() {
    return INSTANCE;
  }

  public void put(String key, Object value, ConfigOrigin origin) {
    ConfigSetting setting = ConfigSetting.of(key, value, origin);
    collected.put(key, setting);
  }

  public void put(String key, Object value, ConfigOrigin origin, String configId) {
    ConfigSetting setting = ConfigSetting.of(key, value, origin, configId);
    collected.put(key, setting);
  }

  public void putAll(Map<String, Object> keysAndValues, ConfigOrigin origin) {
    // attempt merge+replace to avoid collector seeing partial update
    Map<String, ConfigSetting> merged =
        new ConcurrentHashMap<>(keysAndValues.size() + collected.size());
    for (Map.Entry<String, Object> entry : keysAndValues.entrySet()) {
      ConfigSetting setting = ConfigSetting.of(entry.getKey(), entry.getValue(), origin);
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
}
