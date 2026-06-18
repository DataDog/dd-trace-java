package datadog.trace.api;

import static datadog.trace.util.ConfigStrings.propertyNameToEnvironmentVariableName;
import static datadog.trace.util.ConfigStrings.toEnvVar;

import datadog.trace.config.inversion.GeneratedSupportedConfigurations;
import java.util.BitSet;
import java.util.Map;
import java.util.Objects;

public final class ConfigSetting {
  public static final int DEFAULT_SEQ_ID = 1;
  public static final int NON_DEFAULT_SEQ_ID = DEFAULT_SEQ_ID + 1;
  public static final int ABSENT_SEQ_ID = 0;

  public final String key;
  public final Object value;
  public final ConfigOrigin origin;
  public final int seqId;

  /** The config ID associated with this setting, or {@code null} if not applicable. */
  public final String configId;

  public static ConfigSetting of(String key, Object value, ConfigOrigin origin) {
    return new ConfigSetting(key, value, origin, ABSENT_SEQ_ID, null);
  }

  public static ConfigSetting of(String key, Object value, ConfigOrigin origin, int seqId) {
    return new ConfigSetting(key, value, origin, seqId, null);
  }

  // No usages of this function
  public static ConfigSetting of(String key, Object value, ConfigOrigin origin, String configId) {
    return new ConfigSetting(key, value, origin, ABSENT_SEQ_ID, configId);
  }

  public static ConfigSetting of(
      String key, Object value, ConfigOrigin origin, int seqId, String configId) {
    return new ConfigSetting(key, value, origin, seqId, configId);
  }

  private ConfigSetting(String key, Object value, ConfigOrigin origin, int seqId, String configId) {
    this.key = key;
    // Redact values of configs flagged "sensitive": true in metadata/supported-configurations.json.
    // The flags (canonical keys plus their aliases) are compiled into
    // GeneratedSupportedConfigurations.SENSITIVE_KEYS in env-var form by the supported-config
    // generator, so the registry is the single source of truth for what gets hidden. The collected
    // key is canonicalized to the same env-var form before lookup, regardless of which form it was
    // collected under (property name, dd.* system property, alias, or raw env var).
    this.value =
        (value != null
                && GeneratedSupportedConfigurations.SENSITIVE_KEYS.contains(redactionKey(key)))
            ? "<hidden>"
            : value;
    this.origin = origin;
    this.seqId = seqId;
    this.configId = configId;
  }

  public String normalizedKey() {
    // OTel configurations should not be normalized with DD_
    if (key.startsWith("otel.") || key.startsWith("OTEL_")) {
      return toEnvVar(key);
    }
    return propertyNameToEnvironmentVariableName(key);
  }

  // Canonical env-var form used to match a collected key against SENSITIVE_KEYS. Unlike
  // normalizedKey(), this never double-prefixes a key already in DD_ env-var form: the api-key
  // property name, the dd.api-key system property, and a raw DD_API_KEY env var all canonicalize to
  // DD_API_KEY, so redaction matches whichever form the value arrived in.
  private static String redactionKey(String key) {
    if (key.startsWith("otel.") || key.startsWith("OTEL_")) {
      return toEnvVar(key);
    }
    String env = toEnvVar(key);
    return env.startsWith("DD_") ? env : "DD_" + env;
  }

  public String stringValue() {
    if (value == null) {
      return null;
    } else if (value instanceof BitSet) {
      return renderIntegerRange((BitSet) value);
    } else if (value instanceof Map) {
      return renderMap((Map<Object, Object>) value);
    } else if (value instanceof Iterable) {
      return renderIterable((Iterable) value);
    } else {
      return value.toString();
    }
  }

  private static String renderIntegerRange(BitSet bitset) {
    StringBuilder sb = new StringBuilder();
    int start = 0;
    while (true) {
      start = bitset.nextSetBit(start);
      if (start < 0) {
        break;
      }
      int end = bitset.nextClearBit(start);
      if (sb.length() > 0) {
        sb.append(',');
      }
      if (start < end - 1) {
        // interval
        sb.append(start);
        sb.append('-');
        sb.append(end);
      } else {
        // single value
        sb.append(start);
      }
      start = end;
    }
    return sb.toString();
  }

  private static String renderMap(Map<Object, Object> merged) {
    StringBuilder result = new StringBuilder();
    for (Map.Entry entry : merged.entrySet()) {
      if (result.length() > 0) {
        result.append(',');
      }
      result.append(entry.getKey());
      result.append(':');
      result.append(entry.getValue());
    }
    return result.toString();
  }

  private static String renderIterable(Iterable iterable) {
    StringBuilder result = new StringBuilder();
    for (Object entry : iterable) {
      if (result.length() > 0) {
        result.append(',');
      }
      result.append(entry);
    }
    return result.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ConfigSetting that = (ConfigSetting) o;
    return key.equals(that.key)
        && Objects.equals(value, that.value)
        && origin == that.origin
        && seqId == that.seqId
        && Objects.equals(configId, that.configId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, value, origin, seqId, configId);
  }

  @Override
  public String toString() {
    return "ConfigSetting{"
        + "key='"
        + normalizedKey()
        + '\''
        + ", value="
        + stringValue()
        + ", origin="
        + origin
        + ", seqId="
        + seqId
        + ", configId="
        + configId
        + '}';
  }
}
