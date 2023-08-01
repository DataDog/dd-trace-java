package datadog.telemetry.api;

public final class ConfigChange {
  public final String name;
  public final Object value;

  public ConfigChange(String name, Object value) {
    this.name = name;
    this.value = value;
  }
}
