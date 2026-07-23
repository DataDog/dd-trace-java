package datadog.trace.core.otlp.trace;

import static datadog.trace.core.otlp.common.OtlpCommonJson.writeScopeAndSchema;
import static datadog.trace.core.otlp.common.OtlpPayload.JSON_CONTENT_TYPE;
import static datadog.trace.core.otlp.common.OtlpResourceJson.TRACE_RESOURCE_FRAGMENT;
import static datadog.trace.core.otlp.trace.OtlpTraceJson.writeSpan;

import datadog.json.JsonWriter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * Collects Datadog traces and marshals them into a 'trace.proto' JSON payload.
 *
 * <p>This collector is designed to be called by a single thread. To minimize allocations each
 * collection returns a payload only to be used by the calling thread until the next collection.
 * (The payload should be copied before passing it onto another thread.)
 *
 * <p>Unlike the protobuf collector, JSON doesn't need message lengths computed ahead of time, so
 * spans are written directly and in order into the currently open JSON arrays as they're completed.
 * Process tags are attached to the first span written in a scope, matching the {@code
 * firstSpanInPayload} convention used by the other trace mappers. Sampling tags still need a
 * one-span lookahead: that decision (and the corresponding {@code MetadataConsumer.accept} call)
 * can only be made once we see the next span (or reach a scope/payload boundary), since it depends
 * on whether the held-back span is the last one in its trace.
 */
public final class OtlpTraceJsonCollector extends OtlpTraceCollector {

  private static final OtelInstrumentationScope DEFAULT_TRACE_SCOPE =
      new OtelInstrumentationScope("", null, null);

  private JsonWriter writer;
  private OtlpTraceJson.MetaWriter metaWriter;

  private boolean payloadStarted;
  private boolean anySpanWritten;
  private boolean firstSpanInScope;
  private int traceCount;

  private OtelInstrumentationScope currentScope;
  private DDSpan currentSpan;
  private List<? extends AgentSpanLink> currentSpanLinks = Collections.emptyList();

  /** Adds the given trace spans to the collector. */
  @Override
  public void addTrace(List<? extends CoreSpan<?>> spans) {
    if (!payloadStarted) {
      start();
      payloadStarted = true;
    }

    boolean exported = false;
    for (CoreSpan<?> span : spans) {
      exported |= visitSpan(span);
    }
    if (exported) {
      traceCount++;
    }
  }

  /**
   * Marshals the traces collected so far into a JSON payload.
   *
   * <p>This payload is only valid for the calling thread until the next collection.
   */
  @Override
  public OtlpPayload collectTraces() {
    if (!payloadStarted) {
      return OtlpPayload.EMPTY;
    }
    try {
      return completePayload();
    } finally {
      stop();
    }
  }

  /** Prepare temporary elements to collect trace data. */
  private void start() {
    traceCount = 0;

    writer = new JsonWriter();
    metaWriter = new OtlpTraceJson.MetaWriter(writer);

    writer.beginObject();
    writer.name("resourceSpans").beginArray();
    writer.beginObject();
    writer.name("resource").jsonValue(TRACE_RESOURCE_FRAGMENT);
    writer.name("scopeSpans").beginArray();

    // for now put all spans under the default scope
    visitScopedSpans(DEFAULT_TRACE_SCOPE);
  }

  /** Cleanup elements used to collect trace data. */
  private void stop() {
    payloadStarted = false;
    anySpanWritten = false;

    writer = null;
    metaWriter = null;

    currentScope = null;
    currentSpan = null;
    currentSpanLinks = Collections.emptyList();
  }

  @Override
  public int getTraceCount() {
    return traceCount;
  }

  private void visitScopedSpans(OtelInstrumentationScope scope) {
    if (currentScope != null) {
      completeScope();
    }
    currentScope = scope;
    firstSpanInScope = true;

    writer.beginObject();
    writeScopeAndSchema(writer, scope);
    writer.name("spans").beginArray();
  }

  private boolean visitSpan(CoreSpan<?> span) {
    if (!shouldExport(span)) {
      return false;
    }
    if (currentSpan != null) {
      // ensure last span written at trace boundary includes sampling tags
      if (!span.getTraceId().equals(currentSpan.getTraceId())) {
        metaWriter.includeSamplingTags();
      }
      completeSpan();
    }
    currentSpan = (DDSpan) span;
    currentSpanLinks = currentSpan.getLinks();
    return true;
  }

  // called once we've processed all scopes and span messages
  private OtlpPayload completePayload() {
    if (currentScope != null) {
      completeScope();
    }

    writer.endArray(); // scopeSpans
    writer.endObject(); // resourceSpans[0]
    writer.endArray(); // resourceSpans
    writer.endObject(); // root

    if (!anySpanWritten) {
      return OtlpPayload.EMPTY;
    }

    byte[] bytes = writer.toByteArray();
    return new OtlpPayload(ByteBuffer.wrap(bytes), JSON_CONTENT_TYPE);
  }

  // called once we've processed all spans in a specific scope
  private void completeScope() {
    if (currentSpan != null) {
      // ensure last span written at scope boundary includes sampling tags
      metaWriter.includeSamplingTags();
      completeSpan();
    }

    writer.endArray(); // spans
    writer.endObject(); // scopeSpans[0]

    // reset temporary elements for next scope
    currentScope = null;
  }

  // called once we've processed all span-links in a specific span
  private void completeSpan() {
    if (firstSpanInScope) {
      // ensure first span written in the scope includes process tags,
      // matching the firstSpanInPayload convention used by the other trace mappers
      metaWriter.includeProcessTags();
      firstSpanInScope = false;
    }
    writeSpan(writer, currentSpan, metaWriter, currentSpanLinks);
    anySpanWritten = true;

    // reset temporary elements for next span
    currentSpan = null;
    currentSpanLinks = Collections.emptyList();
  }
}
