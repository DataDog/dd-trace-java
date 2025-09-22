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

  /**
   * Updates multiple configuration settings with REMOTE origin.
   *
   * @param configMap map of configuration key-value pairs to add
   */
  public void updateAll(Map<String, Object> configMap) {
    for (Map.Entry<String, Object> entry : configMap.entrySet()) {
      put(entry.getKey(), entry.getValue(), REMOTE, ABSENT_SEQ_ID, null);
    }
  }

  // put method specifically for DEFAULT origins. We don't allow overrides for configs from DEFAULT
  // origins
  public void putDefault(String key, Object value) {
    ConfigSetting setting = ConfigSetting.of(key, value, DEFAULT, DEFAULT_SEQ_ID);
    Map<String, ConfigSetting> configMap = collected.computeIfAbsent(DEFAULT, NEW_SUB_MAP);
    if (!configMap.containsKey(key)) {
      configMap.put(key, setting);
    }
  }

  // report config from Remote Config origin
  public void putRemote(String key, Object value) {
    put(key, value, REMOTE, DEFAULT_SEQ_ID);
  }

  @SuppressWarnings("unchecked")
  public Map<ConfigOrigin, Map<String, ConfigSetting>> collect() {
    if (!collected.isEmpty()) {
      return COLLECTED_UPDATER.getAndSet(this, new ConcurrentHashMap<>());
    } else {
      return Collections.emptyMap();
    }
  }

  // NOTE: Only used to preserve legacy behavior for with smoke tests
  public static ConfigSetting getAppliedConfigSetting(
      String key, Map<ConfigOrigin, Map<String, ConfigSetting>> configMap) {
    ConfigSetting best = null;
    for (Map<String, ConfigSetting> originConfigMap : configMap.values()) {
      ConfigSetting setting = originConfigMap.get(key);
      if (setting != null) {
        if (best == null || setting.seqId > best.seqId) {
          best = setting;
        }
      }
    }
    return best;
  }
}
