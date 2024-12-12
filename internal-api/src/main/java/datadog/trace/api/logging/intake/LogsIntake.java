package datadog.trace.api.logging.intake;

import java.util.Map;

public class LogsIntake {

  private static volatile LogsWriter WRITER;

  public static void registerWriter(LogsWriter writer) {
    WRITER = writer;
  }

  public static void shutdown() {
    LogsWriter writer = WRITER;
    if (writer != null) {
      writer.shutdown();
    }
  }

  public static void log(Map<String, Object> message) {
    LogsWriter writer = WRITER;
    if (writer != null) {
      writer.log(message);
    }
  }
}
