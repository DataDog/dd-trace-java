package datadog.trace.api;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ConfigSetting {
  public final String key;
  public final Object value;
  public final ConfigOrigin origin;

  private static final Set<String> CONFIG_FILTER_LIST =
      new HashSet<>(
          Arrays.asList("DD_API_KEY", "dd.api-key", "dd.profiling.api-key", "dd.profiling.apikey"));

  public static ConfigSetting of(String key, Object value, ConfigOrigin origin) {
    return new ConfigSetting(key, value, origin);
  }

  private ConfigSetting(String key, Object value, ConfigOrigin origin) {
    this.key = key;
    this.value = CONFIG_FILTER_LIST.contains(key) ? "<hidden>" : value;
    this.origin = origin;
  }

  public String normalizedKey() {
    return key.toLowerCase().replace(".", "_");
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
    return key.equals(that.key) && Objects.equals(value, that.value) && origin == that.origin;
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, value, origin);
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
        + '}';
  }
}
