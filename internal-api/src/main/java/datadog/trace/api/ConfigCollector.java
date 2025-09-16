package datadog.trace.api;

import static datadog.trace.api.ConfigOrigin.DEFAULT;
import static datadog.trace.api.ConfigOrigin.REMOTE;
import static datadog.trace.api.ConfigSetting.ABSENT_SEQ_ID;
import static datadog.trace.api.ConfigSetting.DEFAULT_SEQ_ID;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

  private volatile Map<ConfigOrigin, Map<String, ConfigSetting>> collected =
      new ConcurrentHashMap<>();

  private volatile Map<String, AtomicInteger> highestSeqId = new ConcurrentHashMap<>();

  public static ConfigCollector get() {
    return INSTANCE;
  }

  // Sequence ID is critical when a telemetry payload contains multiple entries for the same key and
  // origin. Use this constructor only when you are certain that there will be one entry for the
  // given key and origin.
  void put(String key, Object value, ConfigOrigin origin) {
    put(key, value, origin, ABSENT_SEQ_ID, null);
  }

  public void putRemoteConfig(String key, Object value) {
    int remoteSeqId =
        highestSeqId.containsKey(key) ? highestSeqId.get(key).get() + 1 : DEFAULT_SEQ_ID + 1;
    put(key, value, REMOTE, remoteSeqId, null);
  }

  public void put(String key, Object value, ConfigOrigin origin, int seqId) {
    put(key, value, origin, seqId, null);
  }

  // There are no usages of this function
  public void put(String key, Object value, ConfigOrigin origin, String configId) {
    put(key, value, origin, ABSENT_SEQ_ID, configId);
  }

  public void put(String key, Object value, ConfigOrigin origin, int seqId, String configId) {
    ConfigSetting setting = ConfigSetting.of(key, value, origin, seqId, configId);
    Map<String, ConfigSetting> configMap =
        collected.computeIfAbsent(origin, k -> new ConcurrentHashMap<>());
    configMap.put(key, setting); // replaces any previous value for this key at origin
    highestSeqId.computeIfAbsent(key, k -> new AtomicInteger()).set(seqId);
  }

  // put method specifically for DEFAULT origins. We don't allow overrides for configs from DEFAULT
  // origins
  public void putDefault(String key, Object value) {
    ConfigSetting setting = ConfigSetting.of(key, value, DEFAULT, DEFAULT_SEQ_ID);
    Map<String, ConfigSetting> configMap =
        collected.computeIfAbsent(DEFAULT, k -> new ConcurrentHashMap<>());
    if (!configMap.containsKey(key)) {
      configMap.put(key, setting);
    }
  }

  public void putRemoteConfigPayload(Map<String, Object> keysAndValues, ConfigOrigin origin) {
    for (Map.Entry<String, Object> entry : keysAndValues.entrySet()) {
      putRemoteConfig(entry.getKey(), entry.getValue());
    }
  }

  @SuppressWarnings("unchecked")
  public Map<ConfigOrigin, Map<String, ConfigSetting>> collect() {
    if (!collected.isEmpty()) {
      highestSeqId = new ConcurrentHashMap<>();
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
