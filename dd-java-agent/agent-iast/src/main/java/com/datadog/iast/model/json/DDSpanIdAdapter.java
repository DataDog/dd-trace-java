package com.datadog.iast.model.json;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.api.DDSpanId;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class DDSpanIdAdapter extends JsonAdapter<DDSpanId> {

  @Override
  public void toJson(@Nonnull final JsonWriter writer, final @Nullable DDSpanId value)
      throws IOException {
    if (value == null) {
      writer.nullValue();
      return;
    }
    writer.value(value.toLong());
  }

  @Nullable
  @Override
  public DDSpanId fromJson(@Nonnull final JsonReader reader) throws IOException {
    throw new UnsupportedOperationException("DDSpanId deserialization is not supported");
  }
}
