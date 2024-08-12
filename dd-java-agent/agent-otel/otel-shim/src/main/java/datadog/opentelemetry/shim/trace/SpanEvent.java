package datadog.opentelemetry.shim.trace;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import io.opentelemetry.api.common.Attributes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;

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

  @NotNull
  public static String toTag(List<SpanEvent> events) {
    StringBuilder builder = new StringBuilder("[");
    int index = 0;
    while (index < events.size()) {
      String linkAsJson = getEncoder().toJson(events.get(index));
      if (index > 0) {
        builder.append(',');
      }
      builder.append(linkAsJson);
      index++;
    }
    // TODO: Do we want to enforce a maximum tag size, like TAG_MAX_LENGTH in DDSpanLink? If so,
    // should this limit exist in DDTags instead (to apply to all tags moving forward)?
    // If so, then we can maybe share the json encoder code between SpanEvent and DDSpanLink classes
    return builder.append("]").toString();
  }

  private static JsonAdapter<SpanEvent> getEncoder() {
    return EncoderHolder.ENCODER;
  }

  private static class EncoderHolder {
    static final JsonAdapter<SpanEvent> ENCODER = createEncoder();

    private static JsonAdapter<SpanEvent> createEncoder() {
      Moshi moshi = new Moshi.Builder().add(new SpanEventAdapter()).build();
      return moshi.adapter(SpanEvent.class);
    }
  }

  private static class SpanEventAdapter {
    @ToJson
    SpanEventJson toSpanEventJson(SpanEvent event) {
      SpanEventJson json = new SpanEventJson();
      json.name = event.name;
      json.time_unix_nano = event.timestamp;
      //      if (!link.attributes().isEmpty()) {
      //        json.attributes = link.attributes().asMap();
      //      }
      return json;
    }

    @FromJson
    SpanEvent fromSpanEventJson(SpanEventJson json) {
      return new SpanEvent(
          json.name,
          // attributes
          null,
          json.time_unix_nano,
          TimeUnit.NANOSECONDS);
    }
  }

  private static class SpanEventJson {
    String name;
    long time_unix_nano;
    //    Map<String, String> attributes;
  }
}
