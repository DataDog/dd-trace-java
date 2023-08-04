package datadog.trace.api;

import java.util.Objects;

public final class ConfigSetting {
  public final String key;
  public final Object value;
  public final ConfigOrigin origin;

  public ConfigSetting(String key, Object value, ConfigOrigin origin) {
    this.key = key;
    this.value = value;
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
