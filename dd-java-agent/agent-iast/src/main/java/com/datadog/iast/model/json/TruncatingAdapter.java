package com.datadog.iast.model.json;

import com.squareup.moshi.JsonWriter;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class TruncatingAdapter<V> extends FormattingAdapter<V> {
  public final void toJson(@Nonnull final JsonWriter jsonWriter, @Nullable final V value)
      throws IOException {
    toJson(new TruncatedWriter(jsonWriter), value);
  }

  public abstract void toJson(@Nonnull final TruncatedWriter writer, @Nullable final V value)
      throws IOException;
}
