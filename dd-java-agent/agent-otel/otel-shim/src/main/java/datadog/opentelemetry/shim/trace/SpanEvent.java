package datadog.opentelemetry.shim.trace;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.opentelemetry.api.common.Attributes;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SpanEvent {

  private final long timestamp;
  private final String name;
  // attributes

  public SpanEvent(String name, Attributes attributes) {
    this.name = name;
    this.timestamp = timeNano();
  }

  public SpanEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    this.name = name;
    this.timestamp = timeNano(timestamp, unit);
  }

  private static long timeNano() {
    return System.nanoTime();
  }

  private static long timeNano(long timestamp, TimeUnit unit) {
    return TimeUnit.NANOSECONDS.convert(timestamp, unit);
  }

  public String toString() {
    // TODO if attributes exist, process those
    return "{\"name\":\"" + this.name + "\",\"time_unix_nano\":" + this.timestamp + "}";
  }

  @NonNull
  public static String toTag(List<SpanEvent> events) {
    // TODO: Do we want to enforce a maximum tag size, like TAG_MAX_LENGTH in DDSpanLink?
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
