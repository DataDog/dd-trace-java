package datadog.opentelemetry.shim.trace;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class OtelSpanEvent {

  private String name;
  private long timestamp;

  public OtelSpanEvent(String name) {
    this.name = name;
    this.timestamp = timeNano();
  }

  public OtelSpanEvent(String name, long timestamp, TimeUnit unit) {
    this.name = name;
    this.timestamp = timeNano(timestamp, unit);
  }

  //  public OtelSpanEvent(String name, Instant timestamp) {}

  public OtelSpanEvent(String name, io.opentelemetry.api.common.Attributes attributes) {
    this.name = name;
    this.timestamp = timeNano();
  }

  private static long timeNano() {
    return Instant.now().toEpochMilli() * 1_000_000;
  }

  private static long timeNano(long timestamp, TimeUnit unit) {
    return TimeUnit.NANOSECONDS.convert(timestamp, unit);
  }
}
