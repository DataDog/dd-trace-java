package datadog.trace.api.logging.intake;

import java.util.Map;

public interface LogsWriter {

  void start();

  void log(Map<String, Object> message);

  void shutdown();
}
