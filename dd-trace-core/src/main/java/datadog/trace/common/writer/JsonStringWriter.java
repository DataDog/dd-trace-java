package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.DDId;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class JsonStringWriter implements Writer {
  private final boolean hexIds;
  private final TraceProcessor processor = new TraceProcessor();
  private final JsonAdapter<List<DDSpan>> TRACE_ADAPTER =
      new Moshi.Builder()
          .add(new DDSpanAdapter().FACTORY)
          .build()
          .adapter(Types.newParameterizedType(List.class, DDSpan.class));

  public JsonStringWriter() {
    this(false);
  }

  public JsonStringWriter(final boolean hexIds) {
    this.hexIds = hexIds;
  }

  @Override
  public void write(final List<DDSpan> trace) {
    final List<DDSpan> processedTrace = processor.onTraceComplete(trace);

    final String json;
    try {
      json = TRACE_ADAPTER.toJson(processedTrace);
    } catch (final Exception e) {
      writeException(processedTrace, e);
      return;
    }

    writeJson(json);
  }

  protected abstract void writeJson(String json);

  protected abstract void writeException(List<DDSpan> trace, Exception e);

  private class DDSpanAdapter extends JsonAdapter<DDSpan> {
    public final JsonAdapter.Factory FACTORY =
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
    public void toJson(final com.squareup.moshi.JsonWriter writer, final DDSpan span)
        throws IOException {
      writer.beginObject();
      writer.name("service");
      writer.value(span.getServiceName());
      writer.name("name");
      writer.value(span.getOperationName());
      writer.name("resource");
      writer.value(span.getResourceName().toString());
      writer.name("trace_id");
      writeId(writer, span.getTraceId());
      writer.name("span_id");
      writeId(writer, span.getSpanId());
      writer.name("parent_id");
      writeId(writer, span.getParentId());
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
      for (final Map.Entry<String, Number> entry : span.getMetrics().entrySet()) {
        writer.name(entry.getKey());
        writer.value(entry.getValue());
      }
      writer.endObject();
      writer.name("meta");
      writer.beginObject();
      final Map<String, Object> tags = span.getTags();
      for (final Map.Entry<String, String> entry : span.context().getBaggageItems().entrySet()) {
        if (!tags.containsKey(entry.getKey())) {
          writer.name(entry.getKey());
          writer.value(entry.getValue());
        }
      }
      for (final Map.Entry<String, Object> entry : tags.entrySet()) {
        writer.name(entry.getKey());
        writer.value(String.valueOf(entry.getValue()));
      }
      writer.endObject();
      writer.endObject();
    }

    private void writeId(final com.squareup.moshi.JsonWriter writer, final DDId id)
        throws IOException {
      if (hexIds) {
        writer.value(id.toHexString());
      } else {
        writer.value(id.toLong());
      }
    }
  }
}
