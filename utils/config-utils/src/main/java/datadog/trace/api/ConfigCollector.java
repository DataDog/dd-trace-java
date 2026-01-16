package datadog.trace.api;

import static datadog.trace.api.ConfigOrigin.DEFAULT;
import static datadog.trace.api.ConfigOrigin.REMOTE;
import static datadog.trace.api.ConfigSetting.ABSENT_SEQ_ID;
import static datadog.trace.api.ConfigSetting.DEFAULT_SEQ_ID;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

/**
 * Collects system properties and environment variables set by the user and used by the tracer. Puts
 * to this map will happen in Config and ConfigProvider classes, which can run concurrently with
 * consumers. So this is based on a ConcurrentHashMap to deal with it.
 */
public class ConfigCollector {
  private static final ConfigCollector INSTANCE = new ConfigCollector();

  private ConfigCollector() {}

  private static final AtomicReferenceFieldUpdater<ConfigCollector, Map> COLLECTED_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(ConfigCollector.class, Map.class, "collected");

  private static final Function<ConfigOrigin, Map<String, ConfigSetting>> NEW_SUB_MAP =
      k -> new ConcurrentHashMap<>();

  private volatile Map<ConfigOrigin, Map<String, ConfigSetting>> collected =
      new ConcurrentHashMap<>();

  public static ConfigCollector get() {
    return INSTANCE;
  }

  public void put(String key, Object value, ConfigOrigin origin, int seqId) {
    put(key, value, origin, seqId, null);
  }

  public void put(String key, Object value, ConfigOrigin origin, int seqId, String configId) {
    ConfigSetting setting = ConfigSetting.of(key, value, origin, seqId, configId);
    Map<String, ConfigSetting> configMap = collected.computeIfAbsent(origin, NEW_SUB_MAP);
    configMap.put(key, setting); // replaces any previous value for this key at origin
  }

  // put method specifically for DEFAULT origins. We don't allow overrides for configs from DEFAULT
  // origins
  public void putDefault(String key, Object value) {
    ConfigSetting setting = ConfigSetting.of(key, value, DEFAULT, DEFAULT_SEQ_ID);
    Map<String, ConfigSetting> configMap = collected.computeIfAbsent(DEFAULT, NEW_SUB_MAP);
    configMap.putIfAbsent(key, setting); // don't replace previous default for this key
  }

  /**
   * Report single configuration setting with REMOTE origin.
   *
   * @param key configuration key to report
   * @param value configuration value to report
   */
  public void putRemote(String key, Object value) {
    put(key, value, REMOTE, ABSENT_SEQ_ID);
  }

  /**
   * Report multiple configuration settings with REMOTE origin.
   *
   * @param configMap map of configuration key-value pairs to report
   */
  public void putRemote(Map<String, Object> configMap) {
    // attempt merge+replace to avoid collector seeing partial update
    Map<String, ConfigSetting> merged = new ConcurrentHashMap<>();

    // prepare update
    for (Map.Entry<String, Object> entry : configMap.entrySet()) {
      ConfigSetting setting =
          ConfigSetting.of(entry.getKey(), entry.getValue(), REMOTE, ABSENT_SEQ_ID);
      merged.put(entry.getKey(), setting);
    }

    while (true) {
      // first try adding our update to the map
      Map<String, ConfigSetting> current = collected.putIfAbsent(REMOTE, merged);
      if (current == null) {
        break; // success, no merging required
      }
      // merge existing entries with updated entries
      current.forEach(merged::putIfAbsent);
      if (collected.replace(REMOTE, current, merged)) {
        break; // success, atomically swapped in merged map
      }
      // roll back to original update before next attempt
      merged.keySet().retainAll(configMap.keySet());
    }
  }

  @SuppressWarnings("unchecked")
  public Map<ConfigOrigin, Map<String, ConfigSetting>> collect() {
    if (!collected.isEmpty()) {
      return COLLECTED_UPDATER.getAndSet(this, new ConcurrentHashMap<>());
    } else {
      return Collections.emptyMap();
    }
  }
}
