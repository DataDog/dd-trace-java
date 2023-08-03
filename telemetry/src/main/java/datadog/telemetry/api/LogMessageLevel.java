package datadog.telemetry.api;

public enum LogMessageLevel {
  @com.squareup.moshi.Json(name = "ERROR")
  ERROR("ERROR"),

  @com.squareup.moshi.Json(name = "WARN")
  WARN("WARN"),

  @com.squareup.moshi.Json(name = "DEBUG")
  DEBUG("DEBUG");

  private final String value;

  LogMessageLevel(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }

  public static LogMessageLevel fromValue(String text) {
    for (LogMessageLevel b : LogMessageLevel.values()) {
      if (String.valueOf(b.value).equals(text)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + text + "'");
  }
}
