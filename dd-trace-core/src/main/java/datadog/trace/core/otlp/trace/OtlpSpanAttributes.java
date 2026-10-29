package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_PARTIAL_VERSION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_TOP_LEVEL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_WAS_LONG_RUNNING;
import static datadog.trace.common.writer.ddagent.TraceMapper.ORIGIN_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.PROCESS_TAGS_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.SAMPLING_PRIORITY_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.THREAD_ID;
import static datadog.trace.common.writer.ddagent.TraceMapper.THREAD_NAME;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.Metadata;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Span attributes shared by the OTLP protobuf and JSON trace writers: the keys and baggage rules
 * they use, and a {@link Writer} for the tags a span carries as attributes.
 */
final class OtlpSpanAttributes {

  static final UTF8BytesString RESOURCE_NAME = UTF8BytesString.create("resource.name");
  static final UTF8BytesString OPERATION_NAME = UTF8BytesString.create("operation.name");
  static final UTF8BytesString SPAN_TYPE = UTF8BytesString.create("span.type");

  /*
   * Span fields and intercepted Metadata fields bypass TagMap entry projection. Resolve their
   * keys through the registry here so OpenTelemetry names stay consistent with entry tags.
   */
  static final UTF8BytesString SERVICE_NAME_KEY = otelKey(KnownTags.SERVICE_ID);

  static final UTF8BytesString HTTP_STATUS_CODE_KEY = otelKey(KnownTags.HTTP_STATUS_CODE_ID);

  static final UTF8BytesString HTTP_STATUS_CODE_KEY_DD =
      UTF8BytesString.create(KnownTags.HTTP_STATUS_CODE_NAME);

  /**
   * Keys that baggage must not export as attributes: the attributes the writers emit directly from
   * span fields and intercepted Metadata fields (the span's own value wins), plus _dd.p.tid, whose
   * upper 64 bits the OTLP traceId already carries.
   */
  static final Set<String> SKIPPED_BAGGAGE_KEYS =
      new HashSet<>(
          Arrays.asList(
              "_dd.p.tid",
              SERVICE_NAME_KEY.toString(),
              RESOURCE_NAME.toString(),
              OPERATION_NAME.toString(),
              SPAN_TYPE.toString(),
              SAMPLING_PRIORITY_KEY.toString(),
              DD_MEASURED.toString(),
              DD_TOP_LEVEL.toString(),
              DD_PARTIAL_VERSION.toString(),
              DD_WAS_LONG_RUNNING.toString(),
              THREAD_ID.toString(),
              THREAD_NAME.toString(),
              HTTP_STATUS_CODE_KEY.toString(),
              HTTP_STATUS_CODE_KEY_DD.toString(),
              ORIGIN_KEY.toString(),
              PROCESS_TAGS_KEY.toString()));

  /**
   * True if a span tag is exported under exactly {@code key}, so baggage under that key would
   * duplicate it. TagMap lookups resolve aliases (http.request.method finds http.method), so the
   * matching entry's exported name is compared with the key: it depends on OTel semantics and, for
   * direction-scoped tags, on the span's direction (peer.port is server.port on a client).
   */
  static boolean isExportedTagKey(TagMap tags, String key, int direction, boolean otelSemantics) {
    if (exportsAs(tags.getEntry(key), key, direction, otelSemantics)) {
      return true;
    }
    if (otelSemantics) {
      long directionalTagId = KnownTagCodec.directionalKeyOf(key, direction);
      return directionalTagId != 0
          && exportsAs(tags.getEntry(directionalTagId), key, direction, true);
    }
    return false;
  }

  private static boolean exportsAs(
      TagMap.Entry entry, String key, int direction, boolean otelSemantics) {
    return entry != null
        && key.equals(otelSemantics ? entry.openTelemetryTag(direction) : entry.tag());
  }

  /** The OpenTelemetry-namespace key for a known tag, as named by the registry. */
  private static UTF8BytesString otelKey(long tagId) {
    return UTF8BytesString.create(KnownTagCodec.openTelemetryTagOf(tagId));
  }

  /**
   * Writes the tags an OTLP span carries as attributes: the span's direct tags, plus the span
   * defaults ({@code DD_TRACE_SPAN_TAGS}, {@code env}, ...) it doesn't set itself. The global tags
   * are left to the resource, which carries them once per payload.
   *
   * <p>A span default is only written while the span still carries it: one the span removed, or
   * that interception consumed into a span field (such as {@code resource.name}), is skipped. When
   * the tracer tags need interception they're copied into each span, so global tags then also
   * appear on the span, as before.
   *
   * <p>Not thread-safe: each writer owns one.
   */
  static final class Writer<T> {
    private final T target;
    private final TriConsumer<T, Integer, TagMap.EntryReader> tagWriter;

    // per-span state
    private Integer direction;
    private TagMap spanTags;
    private TagMap spanDefaultTags;
    private final List<String> overriddenDefaults = new ArrayList<>(4);

    Writer(T target, TriConsumer<T, Integer, TagMap.EntryReader> tagWriter) {
      this.target = target;
      this.tagWriter = tagWriter;
    }

    /** Writes the span's attribute tags, naming them for the span's {@code direction}. */
    void write(Metadata metadata, Integer direction) {
      this.direction = direction;
      this.spanTags = metadata.getTags();
      this.spanDefaultTags = metadata.getSpanDefaultTags();
      try {
        spanTags.forEachLocal(this, Writer::writeDirectTag);
        if (!spanDefaultTags.isEmpty()) {
          spanDefaultTags.forEach(this, Writer::writeDefaultTag);
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

  private OtlpSpanAttributes() {}
}
