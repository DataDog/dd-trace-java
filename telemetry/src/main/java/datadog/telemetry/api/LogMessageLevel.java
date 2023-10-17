package datadog.telemetry.api;

public enum LogMessageLevel {
  ERROR("ERROR"),
  WARN("WARN"),
  DEBUG("DEBUG");

  private final String value;

  LogMessageLevel(String value) {
    this.value = value;
  }

  public static LogMessageLevel fromValue(String text) {
    for (LogMessageLevel b : LogMessageLevel.values()) {
      if (String.valueOf(b.value).equals(text)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + text + "'");
  }

  @Override
  public String toString() {
    return value;
  }
}
