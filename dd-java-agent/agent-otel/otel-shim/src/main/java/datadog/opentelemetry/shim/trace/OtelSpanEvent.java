package datadog.opentelemetry.shim.trace;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.opentelemetry.api.common.Attributes;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OtelSpanEvent {

  private final long timestamp;
  private final String name;
  private final AgentSpan.Attributes attributes;
  private static TimeSource timeSource = SystemTimeSource.INSTANCE;

  public OtelSpanEvent(String name, Attributes attributes) {
    this.name = name;
    this.attributes =
        OtelConventions.convertAttributes(attributes, SpanAttributes.Builder.Format.EVENTS);
    this.timestamp = getNanosFromTimeSource();
  }

  public OtelSpanEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    this.name = name;
    this.attributes =
        OtelConventions.convertAttributes(attributes, SpanAttributes.Builder.Format.EVENTS);
    this.timestamp = convertNano(timestamp, unit);
  }

  private static long convertNano(long timestamp, TimeUnit unit) {
    return TimeUnit.NANOSECONDS.convert(timestamp, unit);
  }

  private long getNanosFromTimeSource() {
    return timeSource.getCurrentTimeNanos();
  }

  public static void setTimeSource(TimeSource newTimeSource) {
    timeSource = newTimeSource;
  }

  public String toString() {
    StringBuilder builder =
        new StringBuilder(
            "{\"time_unix_nano\":" + this.timestamp + ",\"name\":\"" + this.name + "\"");
    if (!this.attributes.isEmpty()) {
      builder
          .append(",\"attributes\":")
          .append(SpanAttributes.JSONParser.toJson(this.attributes.asMap()));
    }
    return builder.append('}').toString();
  }

  @NonNull
  public static String toTag(List<OtelSpanEvent> events) {
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < events.size(); i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(events.get(i).toString());
    }
    return builder.append(']').toString();
  }
}
