package com.datadog.iast.model.json;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.api.DDId;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class DDIdAdapter extends JsonAdapter<DDId> {

  @Override
  public void toJson(@Nonnull final JsonWriter writer, final @Nullable DDId value)
      throws IOException {
    if (value == null) {
      writer.nullValue();
      return;
    }
    writer.value(value.toLong());
  }

  @Nullable
  @Override
  public DDId fromJson(@Nonnull final JsonReader reader) throws IOException {
    throw new UnsupportedOperationException("DDId deserialization is not supported");
  }
}
