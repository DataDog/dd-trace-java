package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.util.Map;

public class DDSpanEvent {
  private static TimeSource timeSource = SystemTimeSource.INSTANCE;
  private final String name;
  private final Map<String, Object> attributes;
  private final long timestampNanos;

  public DDSpanEvent(String name, Map<String, Object> attributes) {
    this.name = name;
    this.attributes = attributes;
    this.timestampNanos = timeSource.getCurrentTimeNanos();
  }

  public DDSpanEvent(String name, Map<String, Object> attributes, long timestamp) {
    this.name = name;
    this.attributes = attributes;
    this.timestampNanos = timestamp;
  }

  public String getName() {
    return name;
  }

  public Map<String, Object> getAttributes() {
    return attributes;
  }

  public long getTimestampNanos() {
    return timestampNanos;
  }

  public static void setTimeSource(TimeSource source) {
    timeSource = source;
  }

  public String toJson() {
    StringBuilder json = new StringBuilder();
    json.append("{\"time_unix_nano\":")
        .append(timestampNanos)
        .append(",\"name\":\"")
        .append(name)
        .append("\"");

    if (attributes != null && !attributes.isEmpty()) {
      json.append(",\"attributes\":{");
      boolean first = true;
      for (Map.Entry<String, Object> entry : attributes.entrySet()) {
        if (!first) {
          json.append(",");
        }
        json.append("\"").append(entry.getKey()).append("\":");
        Object value = entry.getValue();
        if (value instanceof String) {
          json.append("\"").append(value).append("\"");
        } else {
          json.append(value);
        }
        first = false;
      }
      json.append("}");
    }

    json.append('}');
    return json.toString();
  }
}
