package com.datadog.iast.model.json;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.ToJson;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.taint.Range;
import datadog.trace.api.iast.taint.Source;
import datadog.trace.api.iast.taint.TaintedObject;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TaintedObjectAdapter {

  @ToJson
  public void toJson(@Nonnull final JsonWriter writer, @Nullable final TaintedObject value)
      throws IOException {
    if (value == null) {
      writer.nullValue();
    } else {
      writer.beginObject();
      final Object target = value.get();
      writer.name("value");
      writer.value(target == null ? "[Value GCed]" : target.toString());
      writer.name("ranges");
      writer.beginArray();
      for (final Range range : (Range[]) value.getRanges()) {
        toJson(writer, range);
      }
      writer.endArray();
      writer.endObject();
    }
  }

  private void toJson(@Nonnull final JsonWriter writer, @Nullable final Range value)
      throws IOException {
    if (value == null) {
      writer.nullValue();
    } else {
      writer.beginObject();
      writer.name("source");
      toJson(writer, value.getSource());
      writer.name("start");
      writer.value(value.getStart());
      writer.name("length");
      writer.value(value.getLength());
      writer.endObject();
    }
  }

  private void toJson(@Nonnull final JsonWriter writer, @Nullable final Source value)
      throws IOException {
    if (value == null) {
      writer.nullValue();
    } else {
      writer.beginObject();
      writer.name("origin");
      writer.value(SourceTypes.toString(value.getOrigin()));
      writer.name("name");
      writer.value(value.getName());
      writer.name("value");
      writer.value(value.getValue());
      writer.endObject();
    }
  }

  @FromJson
  public TaintedObject fromJson(@Nonnull final JsonReader reader) {
    throw new UnsupportedOperationException("TaintedObject deserialization not supported");
  }
}
