package datadog.trace.api;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class ConfigSetting {
  public final String key;
  public final Object value;
  public final ConfigOrigin origin;

  private static final Set<String> CONFIG_FILTER_LIST =
      new HashSet<>(
          Arrays.asList("DD_API_KEY", "dd.api-key", "dd.profiling.api-key", "dd.profiling.apikey"));

  private static Object filterConfigEntry(String key, Object value) {
    return CONFIG_FILTER_LIST.contains(key) ? "<hidden>" : value;
  }

  public ConfigSetting(String key, Object value, ConfigOrigin origin) {
    this.key = key;
    this.value = filterConfigEntry(key, value);
    this.origin = origin;
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
        + key
        + '\''
        + ", value="
        + value
        + ", origin="
        + origin
        + '}';
  }
}
