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

  private volatile Map<String, Map<ConfigOrigin, ConfigSetting>> collected =
      new ConcurrentHashMap<>();

  public static ConfigCollector get() {
    return INSTANCE;
  }

  /**
   * Records the latest ConfigSetting for the given key and origin, replacing any previous value for
   * that (key, origin) pair.
   */
  public void put(String key, Object value, ConfigOrigin origin) {
    ConfigSetting setting = ConfigSetting.of(key, value, origin);
    Map<ConfigOrigin, ConfigSetting> originMap =
        collected.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
    originMap.put(origin, setting); // replaces any previous value for this origin
  }

  public void putAll(Map<String, Object> keysAndValues, ConfigOrigin origin) {
    for (Map.Entry<String, Object> entry : keysAndValues.entrySet()) {
      put(entry.getKey(), entry.getValue(), origin);
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Map<ConfigOrigin, ConfigSetting>> collect() {
    if (!collected.isEmpty()) {
      return COLLECTED_UPDATER.getAndSet(this, new ConcurrentHashMap<>());
    } else {
      return Collections.emptyMap();
    }
  }

  /**
   * Returns the {@link ConfigSetting} with the highest precedence for the given key, or {@code
   * null} if no setting exists for that key.
   */
  public ConfigSetting getAppliedConfigSetting(String key) {
    Map<ConfigOrigin, ConfigSetting> originMap = collected.get(key);
    if (originMap == null || originMap.isEmpty()) {
      return null;
    }
    return originMap.values().stream()
        .max(java.util.Comparator.comparingInt(setting -> setting.origin.precedence))
        .orElse(null);
  }
}
