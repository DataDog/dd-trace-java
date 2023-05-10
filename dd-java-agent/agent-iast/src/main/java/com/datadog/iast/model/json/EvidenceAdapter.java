package com.datadog.iast.model.json;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.model.json.AdapterFactory.Context;
import com.datadog.iast.sensitive.SensitiveHandler;
import com.datadog.iast.sensitive.SensitiveHandler.Tokenizer;
import com.datadog.iast.util.Ranged;
import com.datadog.iast.util.RangedDeque;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EvidenceAdapter extends FormattingAdapter<Evidence> {

  private final JsonAdapter<Source> sourceAdapter;
  private final JsonAdapter<Evidence> defaultAdapter;
  private final JsonAdapter<Evidence> redactedAdapter;

  public EvidenceAdapter(@Nonnull final Moshi moshi) {
    sourceAdapter = moshi.adapter(Source.class, SourceIndex.class);
    defaultAdapter = new DefaultEvidenceAdapter();
    redactedAdapter = new RedactedEvidenceAdapter();
  }

  @Override
  public void toJson(@Nonnull final JsonWriter writer, final @Nullable Evidence evidence)
      throws IOException {
    if (evidence == null || evidence.getValue() == null) {
      writer.nullValue();
      return;
    }
    if (Config.get().isIastRedactionEnabled()) {
      redactedAdapter.toJson(writer, evidence);
    } else {
      defaultAdapter.toJson(writer, evidence);
    }
  }

  private void writeValuePart(@Nonnull final JsonWriter writer, @Nonnull final String value)
      throws IOException {
    if (!value.isEmpty()) {
      writeValuePart(writer, value, null);
    }
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

  private String substring(final String value, final Range range) {
    final int end = Math.min(range.getStart() + range.getLength(), value.length());
    return value.substring(range.getStart(), end);
  }

  private class DefaultEvidenceAdapter extends FormattingAdapter<Evidence> {

    @Override
    public void toJson(@Nonnull final JsonWriter writer, final @Nonnull Evidence evidence)
        throws IOException {
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
        @Nonnull final JsonWriter writer,
        @Nonnull final String value,
        @Nonnull final Range... ranges)
        throws IOException {
      writer.beginArray();
      int start = 0;
      for (Range range : ranges) {
        if (range.getStart() > start) {
          writeValuePart(writer, value.substring(start, range.getStart()));
        }
        final String substring = substring(value, range);
        writeValuePart(writer, substring, range);
        start = range.getStart() + substring.length();
      }
      if (start < value.length()) {
        writeValuePart(writer, value.substring(start));
      }
      writer.endArray();
    }
  }

  private class RedactedEvidenceAdapter extends FormattingAdapter<Evidence> {

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nonnull final Evidence evidence)
        throws IOException {
      final Context ctx = Context.get();
      final Vulnerability vulnerability = ctx.vulnerability;
      if (vulnerability == null) {
        defaultAdapter.toJson(writer, evidence);
      } else {
        final RangedDeque<Range> tainted = taintedRanges(evidence);
        final RangedDeque<Ranged> sensitive = sensitiveRanges(vulnerability.getType(), evidence);
        writer.beginObject();
        if (tainted.isEmpty() && sensitive.isEmpty()) {
          writer.name("value");
          writer.value(evidence.getValue());
        } else {
          writer.name("valueParts");
          toRedactedJson(ctx, writer, evidence.getValue(), tainted, sensitive);
        }
        writer.endObject();
      }
    }

    private void toRedactedJson(
        final Context ctx,
        final JsonWriter writer,
        final String value,
        final RangedDeque<Range> tainted,
        final RangedDeque<Ranged> sensitive)
        throws IOException {
      writer.beginArray();
      int start = 0;
      Range nextTainted = tainted.poll();
      Ranged nextSensitive = sensitive.poll();
      for (int i = 0; i < value.length(); i++) {
        if (nextTainted != null && nextTainted.getStart() == i) {
          writeValuePart(writer, value.substring(start, i));
          // clean up contained sensitive ranges
          while (nextSensitive != null && nextTainted.contains(nextSensitive)) {
            ctx.markAsRedacted(nextTainted.getSource());
            nextSensitive = sensitive.poll();
          }
          if (nextSensitive != null && nextSensitive.intersects(nextTainted)) {
            ctx.markAsRedacted(nextTainted.getSource());
            nextSensitive = nextSensitive.remove(nextTainted).get(0);
          }
          if (ctx.shouldRedact(nextTainted.getSource())) {
            writeRedactedValuePart(writer, nextTainted);
          } else {
            writeValuePart(writer, substring(value, nextTainted), nextTainted);
          }
          start = i + nextTainted.getLength();
          i = start - 1;
          nextTainted = tainted.poll();
        } else if (nextSensitive != null && nextSensitive.getStart() == i) {
          writeValuePart(writer, value.substring(start, i));
          if (nextTainted != null && nextSensitive.intersects(nextTainted)) {
            ctx.markAsRedacted(nextTainted.getSource());
            for (final Ranged entry : nextSensitive.remove(nextTainted)) {
              if (entry.getStart() == i) {
                nextSensitive = entry;
              } else {
                sensitive.addFirst(entry);
              }
            }
          }
          writeRedactedValuePart(writer);
          start = i + nextSensitive.getLength();
          i = start - 1;
          nextSensitive = sensitive.poll();
        }
      }
      if (start < value.length()) {
        writeValuePart(writer, value.substring(start));
      }
      writer.endArray();
    }

    private void writeRedactedValuePart(final JsonWriter writer) throws IOException {
      writer.beginObject();
      writer.name("redacted");
      writer.value(true);
      writer.endObject();
    }

    private void writeRedactedValuePart(final JsonWriter writer, final Range range)
        throws IOException {
      writer.beginObject();
      writer.name("redacted");
      writer.value(true);
      writer.name("source");
      sourceAdapter.toJson(writer, range.getSource());
      writer.endObject();
    }

    private RangedDeque<Range> taintedRanges(final Evidence evidence) {
      return RangedDeque.forArray(evidence.getRanges());
    }

    private RangedDeque<Ranged> sensitiveRanges(
        final VulnerabilityType type, final Evidence evidence) {
      final SensitiveHandler handler = SensitiveHandler.get();
      final Tokenizer tokenizer = handler.tokenizeEvidence(type, evidence);
      return RangedDeque.forTokenizer(tokenizer);
    }
  }
}
