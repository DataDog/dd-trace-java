package com.datadog.iast.model.json;

import com.datadog.iast.model.Source;
import com.datadog.iast.model.json.AdapterFactory.Context;
import com.datadog.iast.model.json.AdapterFactory.RedactionContext;
import datadog.trace.api.Config;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SourceAdapter extends TruncatingAdapter<Source> {

  private final SourceTypeAdapter sourceAdapter;
  private final TruncatingAdapter<Source> defaultAdapter;
  private final TruncatingAdapter<Source> redactedAdapter;

  public SourceAdapter() {
    sourceAdapter = new SourceTypeAdapter();
    defaultAdapter = new TruncatedSourceAdapter();
    redactedAdapter = new RedactedSourceAdapter();
  }

  @Override
  public void toJson(@Nonnull final TruncatedWriter writer, final @Nullable Source source)
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

  private class TruncatedSourceAdapter extends TruncatingAdapter<Source> {

    @Override
    public void toJson(@Nonnull TruncatedWriter writer, @Nonnull Source source) throws IOException {
      writer.beginObject();
      writer.name("origin");
      sourceAdapter.toJson(writer.getDelegated(), source.getOrigin());
      writer.name("name");
      writer.value(source.getName());
      writer.name("value");
      writer.value(source.getValue());
      writer.endObject();
    }
  }

  private class RedactedSourceAdapter extends TruncatingAdapter<Source> {

    @Override
    public void toJson(@Nonnull final TruncatedWriter writer, final @Nonnull Source source)
        throws IOException {
      final RedactionContext ctx = Context.get().getRedaction(source);
      if (ctx.shouldRedact()) {
        toRedactedJson(writer, source, ctx.getRedactedValue());
      } else {
        defaultAdapter.toJson(writer, source);
      }
    }

    private void toRedactedJson(
        final TruncatedWriter writer, final Source source, final String value) throws IOException {
      writer.beginObject();
      writer.name("origin");
      sourceAdapter.toJson(writer.getDelegated(), source.getOrigin());
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
