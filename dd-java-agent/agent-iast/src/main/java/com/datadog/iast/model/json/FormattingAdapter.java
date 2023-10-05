package com.datadog.iast.model.json;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class FormattingAdapter<V> extends JsonAdapter<V> {

  @FromJson
  @Nullable
  @Override
  public final V fromJson(@Nonnull final JsonReader reader) throws IOException {
    throw new UnsupportedOperationException(
        "Deserialization is not supported at " + getClass().getName());
  }
}
