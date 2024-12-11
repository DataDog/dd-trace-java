package datadog.test.agent;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

public class AgentSpan {
  private final String service;
  private final String name;
  private final String resource;

  private final long traceId;
  private final long spanId;
  private final long parentId;

  private final Instant start;
  private final Duration duration;

  private final String type;

  private final int error;

  // Metric = Map<String, Number>
  // Meta = Map<String, String>

  public AgentSpan(String service, String name, String resource, long traceId, long spanId, long parentId, Instant start, Duration duration, String type, int error) {
    this.service = service;
    this.name = name;
    this.resource = resource;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentId = parentId;
    this.start = start;
    this.duration = duration;
    this.type = type;
    this.error = error;
  }

  public String service() {
    return this.service;
  }

  public String name() {
    return this.name;
  }

  public String resource() {
    return this.resource;
  }

  public long traceId() {
    return this.traceId;
  }

  public long spanId() {
    return this.spanId;
  }

  public long parentId() {
    return this.parentId;
  }

  public Instant start() {
    return this.start;
  }

  public Duration duration() {
    return this.duration;
  }

  public String type() {
    return this.type;
  }

  public int error() {
    return this.error;
  }

  @Override
  public String toString() {
    return "{" +
        "service='" + service + '\'' +
        ", name='" + name + '\'' +
        ", resource='" + resource + '\'' +
        ", traceId=" + traceId +
        ", spanId=" + spanId +
        ", parentId=" + parentId +
        ", start=" + start +
        ", duration=" + duration +
        ", type='" + type + '\'' +
        ", error=" + error +
        '}';
  }

  static class DurationAdapter extends JsonAdapter<Duration> {
    @Override
    public Duration fromJson(JsonReader jsonReader) throws IOException {
      return Duration.ofNanos(jsonReader.nextLong());
    }

    @Override
    public void toJson(JsonWriter jsonWriter, Duration duration) throws IOException {
      if (duration == null) {
        jsonWriter.nullValue();
      } else {
        jsonWriter.value(duration.toNanos());
      }
    }
  }

  static class InstantAdapter extends JsonAdapter<Instant> {
    @Override
    public Instant fromJson(JsonReader jsonReader) throws IOException {
      return Instant.ofEpochMilli(jsonReader.nextLong());
    }

    @Override
    public void toJson(JsonWriter jsonWriter, @Nullable Instant instant) throws IOException {
      if (instant == null) {
        jsonWriter.nullValue();
      } else {
        jsonWriter.value(instant.toEpochMilli());
      }
    }
  }
}
