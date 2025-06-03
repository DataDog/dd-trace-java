package datadog.telemetry.api;

public enum LogMessageLevel {
  ERROR,
  WARN,
  DEBUG;

  public static LogMessageLevel fromString(String value) {
    if (value == null) {
      return DEBUG;
    }
    switch (value) {
      case "ERROR":
        return ERROR;
      case "WARN":
        return WARN;
      default:
        return DEBUG;
    }
  }
}
