package com.datadog.iast.model.json;

import com.datadog.iast.model.Source;
import com.datadog.iast.model.json.AdapterFactory.Context;
import com.datadog.iast.model.json.AdapterFactory.RedactionContext;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import java.io.IOException;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SourceAdapter extends FormattingAdapter<Source> {

  private final SourceTypeAdapter sourceAdapter;
  private final JsonAdapter<Source> defaultAdapter;
  private final JsonAdapter<Source> redactedAdapter;

  public SourceAdapter(final Factory factory, final Moshi moshi) {
    sourceAdapter = new SourceTypeAdapter();
    defaultAdapter = moshi.nextAdapter(factory, Source.class, Collections.emptySet());
    redactedAdapter = new RedactedSourceAdapter();
  }

  @Override
  public void toJson(@Nonnull final JsonWriter writer, final @Nullable Source source)
      throws IOException {
    if (source == null) {
      writer.nullValue();
      return;
    }
    if (Config.get().isIastRedactionEnabled()) {
      redactedAdapter.toJson(writer, source);
    } else {
      defaultAdapter.toJson(writer, source);
    }
  }

  private class RedactedSourceAdapter extends FormattingAdapter<Source> {

    @Override
    public void toJson(@Nonnull final JsonWriter writer, final @Nonnull Source source)
        throws IOException {
      final RedactionContext ctx = Context.get().getRedaction(source);
      if (ctx.shouldRedact()) {
        toRedactedJson(writer, source, ctx.getRedactedValue());
      } else {
        defaultAdapter.toJson(writer, source);
      }
    }

    private void toRedactedJson(final JsonWriter writer, final Source source, final String value)
        throws IOException {
      writer.beginObject();
      writer.name("origin");
      sourceAdapter.toJson(writer, source.getOrigin());
      writer.name("name");
      writer.value(source.getName());
      writer.name("redacted");
      writer.value(true);
      writer.name("pattern");
      writer.value(value);
      writer.endObject();
    }
  }
}
