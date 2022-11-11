package com.datadog.iast.model.json;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EvidenceAdapter extends JsonAdapter<Evidence> {

  private final JsonAdapter<Source> sourceAdapter;

  public EvidenceAdapter() {
    sourceAdapter = new AdapterFactory.SourceIndexAdapter();
  }

  @Override
  public void toJson(@Nonnull final JsonWriter writer, final @Nullable Evidence evidence)
      throws IOException {
    if (evidence == null || evidence.getValue() == null) {
      writer.nullValue();
      return;
    }
    writer.beginObject();
    if (evidence.getRanges() == null || evidence.getRanges().length == 0) {
      writer.name("value");
      writer.value(evidence.getValue());
    } else {
      writer.name("valueParts");
      toJsonTaintedValue(writer, evidence.getValue(), evidence.getRanges());
    }
    writer.endObject();
  }

  private void toJsonTaintedValue(
      @Nonnull final JsonWriter writer, @Nonnull final String value, @Nonnull final Range... ranges)
      throws IOException {
    writer.beginArray();
    int start = 0;
    for (Range range : ranges) {
      if (range.getStart() > start) {
        writeValuePart(writer, value.substring(start, range.getStart()));
      }
      writeValuePart(
          writer, value.substring(range.getStart(), range.getStart() + range.getLength()), range);
      start = range.getStart() + range.getLength();
    }
    if (start < value.length()) {
      writeValuePart(writer, value.substring(start));
    }
    writer.endArray();
  }

  private void writeValuePart(@Nonnull final JsonWriter writer, @Nonnull final String value)
      throws IOException {
    writeValuePart(writer, value, null);
  }

  private void writeValuePart(
      @Nonnull final JsonWriter writer, @Nonnull final String value, @Nullable final Range range)
      throws IOException {
    writer.beginObject();
    writer.name("value");
    writer.value(value);
    if (range != null) {
      writer.name("source");
      sourceAdapter.toJson(writer, range.getSource());
    }
    writer.endObject();
  }

  @Nullable
  @Override
  public Evidence fromJson(@Nonnull final JsonReader reader) throws IOException {
    throw new UnsupportedOperationException("Evidence deserialization is not supported");
  }
}
