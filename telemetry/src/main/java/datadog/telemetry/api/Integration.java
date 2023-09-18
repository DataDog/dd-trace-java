package datadog.telemetry.api;

public final class Integration {
  public final String name;
  public final boolean enabled;

  public Integration(String name, boolean enabled) {
    this.name = name;
    this.enabled = enabled;
  }
}
