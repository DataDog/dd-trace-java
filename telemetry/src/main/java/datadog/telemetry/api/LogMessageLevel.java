package datadog.telemetry.api;

import javax.annotation.Nullable;

public enum LogMessageLevel {
  ERROR,
  WARN,
  DEBUG;

  @Nullable
  public static LogMessageLevel fromString(String value) {
    switch (value) {
      case "ERROR":
        return ERROR;
      case "WARN":
        return WARN;
      case "DEBUG":
        return DEBUG;
      default:
        return null;
    }
  }
}
