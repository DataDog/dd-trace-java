package datadog.smoketest;

import java.util.List;

public class CrashTelemetryData extends MinimalTelemetryData {
  List<LogMessage> payload;

  public static class LogMessage {

    public String message;
    public String level;
    public String tags;
  }
}
