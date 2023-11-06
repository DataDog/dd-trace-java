package com.datadog.iast.model.json;

import static com.datadog.iast.model.json.TruncationUtils.writeTruncableValue;

import com.datadog.iast.model.Source;
import com.datadog.iast.model.json.AdapterFactory.Context;
import com.datadog.iast.model.json.AdapterFactory.RedactionContext;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import datadog.trace.api.Config;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SourceAdapter extends FormattingAdapter<Source> {

  private final SourceTypeAdapter sourceTypeAdapter;
  private final JsonAdapter<Source> defaultAdapter;
  private final JsonAdapter<Source> redactedAdapter;

  public SourceAdapter() {
    sourceTypeAdapter = new SourceTypeAdapter();
    defaultAdapter = new DefaultSourceAdapter();
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

  private class DefaultSourceAdapter extends FormattingAdapter<Source> {

    @Override
    public void toJson(@Nonnull JsonWriter writer, @Nonnull Source source) throws IOException {
      writer.beginObject();
      writer.name("origin");
      sourceTypeAdapter.toJson(writer, source.getOrigin());
      writer.name("name");
      writer.value(source.getName());
      writer.name("value");
      writeTruncableValue(writer, source.getValue());
      writer.endObject();
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

    private void toRedactedJson(
        final JsonWriter writer, final Source source, @Nullable final String value)
        throws IOException {
      writer.beginObject();
      writer.name("origin");
      sourceTypeAdapter.toJson(writer, source.getOrigin());
      writer.name("name");
      writer.value(source.getName());
      writer.name("redacted");
      writer.value(true);
      writer.name("pattern");
      writeTruncableValue(writer, value);
      writer.endObject();
    }
  }
}
