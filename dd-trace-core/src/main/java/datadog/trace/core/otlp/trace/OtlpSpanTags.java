package datadog.trace.core.otlp.trace;

import datadog.trace.api.TagMap;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.core.Metadata;
import java.util.ArrayList;
import java.util.List;

/**
 * Visits the tags an OTLP span carries as attributes: the span's direct tags, plus the span
 * defaults ({@code DD_TRACE_SPAN_TAGS}, {@code env}, ...) it doesn't set itself. The global tags
 * are left to the resource, which carries them once per payload.
 *
 * <p>A span default is only written while the span still carries it: one the span removed, or that
 * interception consumed into a span field (such as {@code resource.name}), is skipped. When the
 * tracer tags need interception they're copied into each span, so global tags then also appear on
 * the span, as before.
 *
 * <p>Not thread-safe: each writer owns one.
 */
final class OtlpSpanTags<T> {
  private final T target;
  private final TriConsumer<T, Integer, TagMap.EntryReader> tagWriter;

  // per-span state
  private Integer direction;
  private TagMap spanTags;
  private TagMap spanDefaultTags;
  private final List<String> overriddenDefaults = new ArrayList<>(4);

  OtlpSpanTags(T target, TriConsumer<T, Integer, TagMap.EntryReader> tagWriter) {
    this.target = target;
    this.tagWriter = tagWriter;
  }

  /** Writes the span's attribute tags, naming them for the span's {@code direction}. */
  void write(Metadata metadata, Integer direction) {
    this.direction = direction;
    this.spanTags = metadata.getTags();
    this.spanDefaultTags = metadata.getSpanDefaultTags();
    try {
      spanTags.forEachLocal(this, OtlpSpanTags::writeDirectTag);
      if (!spanDefaultTags.isEmpty()) {
        spanDefaultTags.forEach(this, OtlpSpanTags::writeDefaultTag);
      }
    } finally {
      this.spanTags = null;
      this.spanDefaultTags = null;
      overriddenDefaults.clear();
    }
  }

  private void writeDirectTag(TagMap.EntryReader entry) {
    if (!spanDefaultTags.isEmpty() && spanDefaultTags.containsKey(entry.tag())) {
      overriddenDefaults.add(entry.tag());
    }
    tagWriter.accept(target, direction, entry);
  }

  private void writeDefaultTag(TagMap.EntryReader entry) {
    // skip defaults the span set itself, or no longer carries (removed, or consumed by
    // interception)
    if (!overriddenDefaults.contains(entry.tag()) && spanTags.getEntry(entry.tag()) != null) {
      tagWriter.accept(target, direction, entry);
    }
  }
}
