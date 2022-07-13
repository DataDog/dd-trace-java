package com.datadog.iast.model.json;

import com.datadog.iast.model.SourceType;
import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.ToJson;
import java.io.IOException;
import javax.annotation.Nullable;

class SourceTypeAdapter extends JsonAdapter<SourceType> {

  @Override
  @ToJson
  public void toJson(JsonWriter writer, @Nullable SourceType value) throws IOException {
    if (value == null) {
      writer.nullValue();
      return;
    }
    writer.value(value.key);
  }

  @Override
  @Nullable
  @FromJson
  public SourceType fromJson(JsonReader reader) throws IOException {
    throw new UnsupportedOperationException("SourceType deserialization not supported");
  }
}
