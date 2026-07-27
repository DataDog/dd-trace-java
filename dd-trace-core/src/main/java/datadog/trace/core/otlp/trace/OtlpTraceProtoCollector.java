package datadog.trace.core.otlp.trace;

import static datadog.trace.core.otlp.common.OtlpResourceProto.TRACE_RESOURCE_MESSAGE;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordScopedSpansMessage;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordSpanLinkMessage;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordSpanMessage;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpCommonProto;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpProtoBuffer;
import java.util.List;

/**
 * Collects Datadog traces and marshals them into a chunked 'trace.proto' payload.
 *
 * <p>This collector is designed to be called by a single thread. To minimize allocations each
 * collection returns a payload only to be used by the calling thread until the next collection.
 * (The payload should be copied before passing it onto another thread.)
 *
 * <p>We use a single temporary buffer to prepare message chunks at different nesting levels. First
 * we chunk all span-links for a given span. Once the span is complete we add the first part of the
 * span message and its chunked span-links to the scoped chunks. Once the scope is complete we add
 * the first part of the scoped spans message and all its chunks (span messages and any span-links)
 * to the payload. Once all the span data has been chunked we add the enclosing resource span
 * message to the start of the payload.
 */
public final class OtlpTraceProtoCollector extends OtlpTraceCollector {

  private static final OtelInstrumentationScope DEFAULT_TRACE_SCOPE =
      new OtelInstrumentationScope("", null, null);

  private final GrowableBuffer buf = new GrowableBuffer(512);
  private final OtlpTraceProto.MetaWriter metaWriter = new OtlpTraceProto.MetaWriter(buf);
  private final OtlpProtoBuffer protobuf = new OtlpProtoBuffer(8192);

  private boolean payloadStarted;

  // total number of chunked bytes at different nesting levels
  private int payloadBytes;
  private int scopedBytes;
  private int spanBytes;

  private OtelInstrumentationScope currentScope;
  private DDSpan currentSpan;

  /** Adds the given trace spans to the collector. */
  @Override
  public void addTrace(List<? extends CoreSpan<?>> spans) {
    if (!payloadStarted) {
      start();
      payloadStarted = true;
    }

    try {
      // OtlpProtoBuffer collects spans in reverse
      for (int i = spans.size() - 1; i >= 0; i--) {
        visitSpan(spans.get(i));
      }
    } catch (Throwable e) {
      // reset the buffer for subsequent traces
      stop();
      throw e;
    }
  }

  @Override
  public int sizeInBytes() {
    return protobuf.sizeInBytes();
  }

  /**
   * Marshals the traces collected so far into a chunked payload.
   *
   * <p>This payload is only valid for the calling thread until the next collection.
   */
  @Override
  public OtlpPayload collectTraces() {
    try {
      return completePayload();
    } finally {
      stop();
    }
  }

  /** Prepare temporary elements to collect trace data. */
  private void start() {

    // remove stale entries from caches
    OtlpCommonProto.recalibrateCaches();

    // for now put all spans under the default scope
    visitScopedSpans(DEFAULT_TRACE_SCOPE);
  }

  /** Cleanup elements used to collect trace data. */
  private void stop() {
    payloadStarted = false;

    buf.reset();
    protobuf.reset();

    payloadBytes = 0;
    scopedBytes = 0;
    spanBytes = 0;

    currentScope = null;
    currentSpan = null;
  }

  private void visitScopedSpans(OtelInstrumentationScope scope) {
    if (currentScope != null) {
      completeScope();
    }
    currentScope = scope;
  }

  private void visitSpan(CoreSpan<?> span) {
    if (shouldExport(span)) {
      if (currentSpan != null) {
        // ensure last span written at trace boundary includes sampling tags
        // payload buffer is prepending, so last span written appears first!
        if (!span.getTraceId().equals(currentSpan.getTraceId())) {
          metaWriter.includeSamplingTags();
        }
        completeSpan();
      }
      currentSpan = (DDSpan) span;
      currentSpan.getLinks().forEach(this::visitSpanLink);
    }
  }

  private void visitSpanLink(AgentSpanLink spanLink) {
    spanBytes += recordSpanLinkMessage(buf, spanLink, protobuf);
  }

  // called once we've processed all scopes and span messages
  private OtlpPayload completePayload() {
    if (currentScope != null) {
      completeScope();
    }

    if (payloadBytes == 0) {
      return OtlpPayload.EMPTY;
    }

    // prepend the canned resource chunk
    payloadBytes += protobuf.recordMessage(TRACE_RESOURCE_MESSAGE);

    // finally prepend the total length of all collected chunks
    protobuf.recordMessage(buf, 1, payloadBytes);
    return protobuf.toPayload();
  }

  // called once we've processed all spans in a specific scope
  private void completeScope() {
    if (currentSpan != null) {
      // ensure last span written at scope boundary includes process+sampling tags
      // payload buffer is prepending, so last span written appears first!
      metaWriter.includeProcessTags();
      metaWriter.includeSamplingTags();
      completeSpan();
    }

    if (scopedBytes > 0) {
      payloadBytes += recordScopedSpansMessage(buf, currentScope, scopedBytes, protobuf);
    }

    // reset temporary elements for next scope
    currentScope = null;
    scopedBytes = 0;
  }

  // called once we've processed all span-links in a specific span
  private void completeSpan() {

    scopedBytes += recordSpanMessage(buf, currentSpan, metaWriter, spanBytes, protobuf);

    // reset temporary elements for next span
    currentSpan = null;
    spanBytes = 0;
  }
}
