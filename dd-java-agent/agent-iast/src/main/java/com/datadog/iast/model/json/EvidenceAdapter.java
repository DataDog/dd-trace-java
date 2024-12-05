package com.datadog.iast.model.json;

import static com.datadog.iast.model.json.TruncationUtils.writeTruncableValue;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.model.json.AdapterFactory.Context;
import com.datadog.iast.model.json.AdapterFactory.RedactionContext;
import com.datadog.iast.sensitive.SensitiveHandler;
import com.datadog.iast.sensitive.SensitiveHandler.Tokenizer;
import com.datadog.iast.util.Ranged;
import com.datadog.iast.util.RangedDeque;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EvidenceAdapter extends FormattingAdapter<Evidence> {

  private static final Logger log = LoggerFactory.getLogger(EvidenceAdapter.class);

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

  private String substring(final String value, final Ranged range) {
    int end = Math.min(range.getStart() + range.getLength(), value.length());
    if (end < 0) {
      log.debug("Invalid negative end parameter for substring. Value: {} Range: {}", value, range);
      end = value.length();
    }
    return value.substring(range.getStart(), end);
  }

  private static void writeSecureMarks(
      final JsonWriter writer, final @Nullable Set<VulnerabilityType> markedVulnerabilities)
      throws IOException {
    if (markedVulnerabilities == null || markedVulnerabilities.isEmpty()) {
      return;
    }
    writer.name("secure_marks");
    writer.beginArray();
    for (VulnerabilityType type : markedVulnerabilities) {
      writer.value(type.name());
    }
    writer.endArray();
  }

  private class DefaultEvidenceAdapter extends FormattingAdapter<Evidence> {

    @Override
    public void toJson(@Nonnull final JsonWriter writer, final @Nullable Evidence evidence)
        throws IOException {
      if (evidence == null) {
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
        writeSecureMarks(writer, range.getMarkedVulnerabilities());
      }
      writer.endObject();
    }
  }

  private class RedactedEvidenceAdapter extends FormattingAdapter<Evidence> {

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Evidence evidence)
        throws IOException {
      if (evidence == null) {
        writer.nullValue();
        return;
      }
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
      for (final Iterator<ValuePart> it = new ValuePartIterator(ctx, value, tainted, sensitive);
          it.hasNext(); ) {
        final ValuePart next = it.next();
        if (next != null) {
          next.write(ctx, writer);
        }
      }
      writer.endArray();
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

  private class ValuePartIterator implements Iterator<ValuePart> {
    private final Context ctx;
    private final String value;
    private final RangedDeque<Range> tainted;
    private final RangedDeque<Ranged> sensitive;
    private final Map<Range, List<Ranged>> intersections = new HashMap<>();
    private final Queue<ValuePart> next = new LinkedList<>();
    private int index;

    private ValuePartIterator(
        final Context ctx,
        final String value,
        final RangedDeque<Range> tainted,
        final RangedDeque<Ranged> sensitive) {
      this.ctx = ctx;
      this.value = value;
      this.tainted = tainted;
      this.sensitive = sensitive;
    }

    @Override
    public boolean hasNext() {
      return !next.isEmpty() || index < value.length();
    }

    @Nullable
    @Override
    public ValuePart next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      if (!next.isEmpty()) {
        return next.poll();
      }
      if (tainted.isEmpty() && sensitive.isEmpty()) {
        return nextStringValuePart(value.length()); // last string chunk
      }
      final Range nextTainted = this.tainted.poll();
      Ranged nextSensitive = this.sensitive.poll();
      if (nextTainted != null) {
        if (nextTainted.isBefore(nextSensitive)) {
          addNextStringValuePart(nextTainted.getStart(), next); // pending string chunk
          nextSensitive = handleTaintedValue(nextTainted, nextSensitive);
        } else {
          tainted.addFirst(nextTainted);
        }
      }
      if (nextSensitive != null) {
        if (nextSensitive.isBefore(nextTainted)) {
          addNextStringValuePart(nextSensitive.getStart(), next); // pending string chunk
          handleSensitiveValue(nextSensitive);
        } else {
          sensitive.addFirst(nextSensitive);
        }
      }
      return next.poll();
    }

    @Nullable
    private Ranged handleTaintedValue(
        @Nonnull final Range nextTainted, @Nullable Ranged nextSensitive) {
      final RedactionContext redactionCtx = ctx.getRedaction(nextTainted.getSource());
      List<Ranged> intersections = this.intersections.remove(nextTainted);
      intersections = intersections == null ? new LinkedList<>() : intersections;

      // remove fully overlapped sensitive ranges
      while (nextSensitive != null && nextTainted.contains(nextSensitive)) {
        redactionCtx.markWithSensitiveRanges();
        intersections.add(nextSensitive);
        nextSensitive = sensitive.poll();
      }

      final Ranged intersection;
      // truncate last sensitive range if intersects with the tainted one
      if (nextSensitive != null
          && (intersection = nextTainted.intersection(nextSensitive)) != null) {
        redactionCtx.markWithSensitiveRanges();
        intersections.add(intersection);
        nextSensitive = removeTaintedRange(nextSensitive, nextTainted);
      }

      // finally add value part
      final String taintedValue = substring(value, nextTainted);
      next.add(
          new RedactableTaintedValuePart(sourceAdapter, nextTainted, taintedValue, intersections));
      index = nextTainted.getStart() + nextTainted.getLength();
      return nextSensitive;
    }

    private void handleSensitiveValue(@Nonnull Ranged nextSensitive) {
      // truncate sensitive part if intersects with the next tainted range
      final Range nextTainted = tainted.peek();
      final Ranged intersection;
      if (nextTainted != null && (intersection = nextTainted.intersection(nextSensitive)) != null) {
        final RedactionContext redactionCtx = ctx.getRedaction(nextTainted.getSource());
        redactionCtx.markWithSensitiveRanges();
        intersections.computeIfAbsent(nextTainted, r -> new LinkedList<>()).add(intersection);
        nextSensitive = removeTaintedRange(nextSensitive, nextTainted);
      }

      // finally add value part
      if (nextSensitive != null) {
        final String sensitiveValue = substring(value, nextSensitive);
        next.add(new RedactedValuePart(sensitiveValue));
        index = nextSensitive.getStart() + nextSensitive.getLength();
      }
    }

    /**
     * Removes the tainted range from the sensitive one and returns whatever is before and enqueues
     * the rest
     */
    @Nullable
    private Ranged removeTaintedRange(final Ranged sensitive, final Range tainted) {
      final List<Ranged> disjointRanges = sensitive.remove(tainted);
      Ranged result = null;
      for (final Ranged disjoint : disjointRanges) {
        if (disjoint.isBefore(tainted)) {
          result = disjoint;
        } else {
          this.sensitive.addFirst(disjoint);
        }
      }
      return result;
    }

    @Nullable
    private ValuePart nextStringValuePart(final int end) {
      if (index < end) {
        final String chunk = value.substring(index, end);
        index = end;
        return new StringValuePart(chunk);
      }
      return null;
    }

    private void addNextStringValuePart(final int end, final Collection<ValuePart> target) {
      final ValuePart part = nextStringValuePart(end);
      if (part != null) {
        target.add(part);
      }
    }
  }

  interface ValuePart {
    void write(final Context ctx, final JsonWriter writer) throws IOException;
  }

  static class StringValuePart implements ValuePart {

    @Nullable private final String value;

    private StringValuePart(@Nullable final String value) {
      this.value = value;
    }

    @Override
    public void write(final Context ctx, final JsonWriter writer) throws IOException {
      if (value == null || value.isEmpty()) {
        return;
      }
      writer.beginObject();
      writer.name("value");
      writeTruncableValue(writer, value);
      writer.endObject();
    }
  }

  static class RedactedValuePart implements ValuePart {

    private final String value;

    private RedactedValuePart(final String value) {
      this.value = value;
    }

    @Override
    public void write(final Context ctx, final JsonWriter writer) throws IOException {
      if (value == null) {
        return;
      }
      writer.beginObject();
      writer.name("redacted");
      writer.value(true);
      writer.endObject();
    }
  }

  static class RedactableTaintedValuePart implements ValuePart {

    private final JsonAdapter<Source> adapter;

    private final Source source;

    private final String value;

    private final List<Ranged> sensitiveRanges;

    @Nullable private final Set<VulnerabilityType> markedTypes;

    private RedactableTaintedValuePart(
        final JsonAdapter<Source> adapter,
        final Range range,
        final String value,
        final List<Ranged> sensitive) {
      this.adapter = adapter;
      this.source = range.getSource();
      this.value = value;
      // shift ranges to the start of the tainted range and sort them
      this.sensitiveRanges =
          sensitive.stream()
              .map(it -> shift(it, -range.getStart()))
              .sorted(Comparator.comparing(Ranged::getStart))
              .collect(Collectors.toList());

      this.markedTypes = range.getMarkedVulnerabilities();
    }

    @Override
    public void write(final Context ctx, final JsonWriter writer) throws IOException {
      final RedactionContext redaction = ctx.getRedaction(source);
      redaction.setMarkedTypes(markedTypes);
      if (redaction.shouldRedact()) {
        for (final ValuePart part : split(redaction)) {
          part.write(ctx, writer);
        }
      } else {
        writer.beginObject();
        writer.name("value");
        writeTruncableValue(writer, value);
        writer.name("source");
        adapter.toJson(writer, source);
        writeSecureMarks(writer, markedTypes);
        writer.endObject();
      }
    }

    private List<ValuePart> split(final RedactionContext redaction) {
      final List<ValuePart> parts = new ArrayList<>();
      if (redaction.isSensitive()) {
        // redact the full tainted value as the source is sensitive (password, certificate, ...)
        addValuePart(0, value.length(), redaction, true, parts);
      } else {
        // redact only sensitive parts
        int index = 0;
        for (final Ranged sensitive : this.sensitiveRanges) {
          final int start = sensitive.getStart();
          final int end = sensitive.getStart() + sensitive.getLength();
          // append previous tainted chunk (if any)
          addValuePart(index, start, redaction, false, parts);
          // append current sensitive tainted chunk
          addValuePart(start, end, redaction, true, parts);
          index = end;
        }
        // append last tainted chunk (if any)
        addValuePart(index, value.length(), redaction, false, parts);
      }
      return parts;
    }

    private void addValuePart(
        final int start,
        final int end,
        final RedactionContext ctx,
        final boolean redact,
        final List<ValuePart> valueParts) {
      if (start < end) {
        final Source source = ctx.getSource();
        final String chunk = value.substring(start, end);
        final Set<VulnerabilityType> markedTypes = ctx.getMarkedTypes();
        if (!redact) {
          // append the value
          valueParts.add(new TaintedValuePart(adapter, source, chunk, false, markedTypes));
        } else {
          final int length = chunk.length();
          final String sourceValue = source.getValue();
          final String redactedValue = ctx.getRedactedValue();
          final int matching = (sourceValue == null) ? -1 : sourceValue.indexOf(chunk);
          final String pattern;
          if (matching >= 0 && redactedValue != null) {
            // if matches append the matching part from the redacted value
            pattern = redactedValue.substring(matching, matching + length);
          } else {
            // otherwise redact the string
            pattern = SensitiveHandler.get().redactString(chunk);
          }
          valueParts.add(new TaintedValuePart(adapter, source, pattern, true, markedTypes));
        }
      }
    }

    private Ranged shift(final Ranged ranged, final int offset) {
      return Ranged.build(ranged.getStart() + offset, ranged.getLength());
    }
  }

  static class TaintedValuePart implements ValuePart {
    private final JsonAdapter<Source> adapter;

    private final Source source;

    private final String value;

    private final boolean redacted;

    @Nullable private final Set<VulnerabilityType> markedTypes;

    private TaintedValuePart(
        final JsonAdapter<Source> adapter,
        final Source source,
        final String value,
        final boolean redacted,
        final @Nullable Set<VulnerabilityType> markedTypes) {
      this.adapter = adapter;
      this.source = source;
      this.value = value;
      this.redacted = redacted;
      this.markedTypes = markedTypes;
    }

    @Override
    public void write(final Context ctx, final JsonWriter writer) throws IOException {
      if (value == null) {
        return;
      }
      writer.beginObject();
      writer.name("source");
      adapter.toJson(writer, source);
      if (redacted) {
        writer.name("redacted");
        writer.value(true);
        writer.name("pattern");
      } else {
        writer.name("value");
      }
      writeTruncableValue(writer, value);
      writeSecureMarks(writer, markedTypes);
      writer.endObject();
    }
  }
}
