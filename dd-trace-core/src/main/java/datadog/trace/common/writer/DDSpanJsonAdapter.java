package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;

class DDSpanJsonAdapter extends JsonAdapter<DDSpan> {
  private final boolean hexIds;

  DDSpanJsonAdapter(final boolean hexIds) {
    this.hexIds = hexIds;
  }

  public static Factory buildFactory(final boolean hexIds) {
    return new Factory() {
      @Override
      public JsonAdapter<?> create(
          final Type type, final Set<? extends Annotation> annotations, final Moshi moshi) {
        final Class<?> rawType = Types.getRawType(type);
        if (rawType.isAssignableFrom(DDSpan.class)) {
          return new DDSpanJsonAdapter(hexIds);
        }
        return null;
      }
    };
  }

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
    writer.value(span.getOperationName().toString());
    writer.name("resource");
    writer.value(span.getResourceName().toString());
    writer.name("trace_id");
    writeTraceId(writer, span.getTraceId());
    writer.name("span_id");
    writeSpanId(writer, span.getSpanId());
    writer.name("parent_id");
    writeSpanId(writer, span.getParentId());
    writer.name("start");
    writer.value(span.getStartTime());
    writer.name("duration");
    writer.value(span.getDurationNano());
    writer.name("type");
    writer.value(span.getSpanType());
    writer.name("error");
    writer.value(span.getError());
    writer.name("metrics");
    writer.beginObject();
    for (final Map.Entry<String, Object> entry : span.getTags().entrySet()) {
      if (entry.getValue() instanceof Number) {
        writer.name(entry.getKey());
        writer.value((Number) entry.getValue());
      }
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
      if (!(entry.getValue() instanceof Number)) {
        writer.name(entry.getKey());
        writer.value(String.valueOf(entry.getValue()));
      }
    }
    writer.endObject();
    writer.endObject();
  }

  private void writeTraceId(final com.squareup.moshi.JsonWriter writer, final DDTraceId id)
      throws IOException {
    if (hexIds) {
      writer.value(id.toHexString());
    } else {
      writer.value(id.toLong());
    }
  }

  private void writeSpanId(final com.squareup.moshi.JsonWriter writer, final long id)
      throws IOException {
    if (hexIds) {
      writer.value(DDSpanId.toHexString(id));
    } else {
      writer.value(id);
    }
  }
}
