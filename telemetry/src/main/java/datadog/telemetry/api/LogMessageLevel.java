package datadog.telemetry.api;

public enum LogMessageLevel {
  ERROR("ERROR"),
  WARN("WARN"),
  DEBUG("DEBUG");

  private final String value;

  LogMessageLevel(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return value;
  }
}
