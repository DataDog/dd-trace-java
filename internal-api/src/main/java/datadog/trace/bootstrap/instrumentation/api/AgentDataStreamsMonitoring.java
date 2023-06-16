package datadog.trace.bootstrap.instrumentation.api;

import java.util.LinkedHashMap;

public interface AgentDataStreamsMonitoring {
  void trackBacklog(LinkedHashMap<String, String> sortedTags, long value);
}
