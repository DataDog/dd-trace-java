package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingWriter implements Writer {
  private final TraceProcessor processor = new TraceProcessor();
  private static final JsonAdapter<List<DDSpan>> TRACE_ADAPTER =
      new Moshi.Builder()
          .add(DDSpanAdapter.FACTORY)
          .build()
          .adapter(Types.newParameterizedType(List.class, DDSpan.class));

  @Override
  public void write(List<DDSpan> trace) {
    trace = processor.onTraceComplete(trace);
    try {
      log.info("write(trace): {}", toString(trace));
    } catch (final Exception e) {
      log.error("error writing(trace): {}", trace);
    }
  }

  private String toString(final List<DDSpan> trace) {
    return TRACE_ADAPTER.toJson(trace);
  }

  @Override
  public void incrementTraceCount() {
    log.info("incrementTraceCount()");
  }

  @Override
  public void close() {
    log.info("close()");
  }

  @Override
  public void start() {
    log.info("start()");
  }

  @Override
  public String toString() {
    return "LoggingWriter { }";
  }

  static class DDSpanAdapter extends JsonAdapter<DDSpan> {
    public static final JsonAdapter.Factory FACTORY =
        new JsonAdapter.Factory() {
          @Override
          public JsonAdapter<?> create(
              final Type type, final Set<? extends Annotation> annotations, final Moshi moshi) {
            final Class<?> rawType = Types.getRawType(type);
            if (rawType.isAssignableFrom(DDSpan.class)) {
              return new DDSpanAdapter();
            }
            return null;
          }
        };

    @Override
    public DDSpan fromJson(final JsonReader reader) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void toJson(final JsonWriter writer, final DDSpan span) throws IOException {
      writer.beginObject();
      writer.name("service");
      writer.value(span.getServiceName());
      writer.name("name");
      writer.value(span.getOperationName());
      writer.name("resource");
      writer.value(span.getResourceName().toString());
      writer.name("trace_id");
      writer.value(span.getTraceId().toLong());
      writer.name("span_id");
      writer.value(span.getSpanId().toLong());
      writer.name("parent_id");
      writer.value(span.getParentId().toLong());
      writer.name("start");
      writer.value(span.getStartTime());
      writer.name("duration");
      writer.value(span.getDurationNano());
      writer.name("type");
      writer.value(span.getType());
      writer.name("error");
      writer.value(span.getError());
      writer.name("metrics");
      writer.beginObject();
      for (Map.Entry<String, Number> entry : span.getMetrics().entrySet()) {
        writer.name(entry.getKey());
        writer.value(entry.getValue());
      }
      writer.endObject();
      writer.name("meta");
      writer.beginObject();
      Map<String, Object> tags = span.getTags();
      for (Map.Entry<String, String> entry : span.context().getBaggageItems().entrySet()) {
        if (!tags.containsKey(entry.getKey())) {
          writer.name(entry.getKey());
          writer.value(entry.getValue());
        }
      }
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        writer.name(entry.getKey());
        writer.value(String.valueOf(entry.getValue()));
      }
      writer.endObject();
      writer.endObject();
    }
  }
}
