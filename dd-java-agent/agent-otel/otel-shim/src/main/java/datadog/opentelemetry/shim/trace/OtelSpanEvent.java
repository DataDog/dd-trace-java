package datadog.opentelemetry.shim.trace;

import io.opentelemetry.api.common.Attributes;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OtelSpanEvent {
  private final long timestamp;
  private final String name;
  // attributes

  public OtelSpanEvent(String name, Attributes attributes) {
    this.name = name;
    this.timestamp = timeNano();
  }

  public OtelSpanEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    this.name = name;
    this.timestamp = timeNano(timestamp, unit);
  }

  private static long timeNano() {
    return System.nanoTime();
  }

  private static long timeNano(long timestamp, TimeUnit unit) {
    return TimeUnit.NANOSECONDS.convert(timestamp, unit);
    // MTOFF: Should we handle the case where the conversion returns Long.MIN_VALUE or
    // Long.MAX_VALUE? see:
    // https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/TimeUnit.html#convert-long-java.util.concurrent.TimeUnit-
  }

  public String toString() {
    // TODO if attributes exist, process those
    return "{\"name\":\""
        + this.name
        + "\",\"time_unix_nano\":"
        + Long.toString(this.timestamp)
        + "}";
  }

  //  @NotNull
  // TODO: give this a notnull annotation
  public static String toTag(List<OtelSpanEvent> events) {
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < events.size(); i++) {
      if (i > 0) {
        builder.append(",");
      }
      builder.append(events.get(i).toString());
    }
    return builder.append("]").toString();
  }
}
