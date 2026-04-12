package datadog.trace.core.otlp.trace;

import static datadog.trace.core.otlp.common.OtlpCommonProto.recordMessage;
import static datadog.trace.core.otlp.common.OtlpResourceProto.RESOURCE_MESSAGE;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordScopedSpansMessage;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordSpanLinkMessage;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordSpanMessage;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpCommonProto;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Collects Datadog traces and marshalls them into a chunked 'trace.proto' payload.
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
public final class OtlpTraceProtoCollector implements OtlpTraceCollector {

  public static final OtlpTraceProtoCollector INSTANCE = new OtlpTraceProtoCollector();

  private static final OtelInstrumentationScope DEFAULT_TRACE_SCOPE =
      new OtelInstrumentationScope("", null, null);

  private static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";

  private final GrowableBuffer buf = new GrowableBuffer(512);
  private final OtlpTraceProto.MetaWriter metaWriter = new OtlpTraceProto.MetaWriter(buf);

  // temporary collections of chunks at different nesting levels
  private final Deque<byte[]> payloadChunks = new ArrayDeque<>();
  private final List<byte[]> scopedChunks = new ArrayList<>();
  private final List<byte[]> spanChunks = new ArrayList<>();

  // total number of chunked bytes at different nesting levels
  private int payloadBytes;
  private int scopedBytes;
  private int spanBytes;

  private OtelInstrumentationScope currentScope;
  private DDSpan currentSpan;

  /**
   * Collects trace spans and marshalls them into a chunked payload.
   *
   * <p>This payload is only valid for the calling thread until the next collection.
   */
  @Override
  public OtlpPayload collectSpans(List<DDSpan> spans) {
    OtlpCommonProto.recalibrateCaches();
    start();
    try {
      // for now put all spans under the default scope
      visitScopedSpans(DEFAULT_TRACE_SCOPE);
      spans.forEach(this::visitSpan);
      return completePayload();
    } finally {
      stop();
    }
  }

  /** Prepare temporary elements to collect trace data. */
  private void start() {
    // clear payloadChunks in case it wasn't fully consumed via OtlpPayload
    payloadChunks.clear();
  }

  /** Cleanup elements used to collect trace data. */
  private void stop() {
    buf.reset();
    metaWriter.reset();

    // leave payloadChunks in place so it can be consumed via OtlpPayload
    scopedChunks.clear();
    spanChunks.clear();

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

  private void visitSpan(DDSpan span) {
    if (currentSpan != null) {
      completeSpan();
    }
    currentSpan = span;
    span.getLinks().forEach(this::visitSpanLink);
  }

  private void visitSpanLink(AgentSpanLink spanLink) {
    byte[] spanLinkMessage = recordSpanLinkMessage(buf, spanLink);
    spanChunks.add(spanLinkMessage);
    spanBytes += spanLinkMessage.length;
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
    payloadChunks.addFirst(RESOURCE_MESSAGE);
    payloadBytes += RESOURCE_MESSAGE.length;

    // finally prepend the total length of all collected chunks
    byte[] prefix = recordMessage(buf, 1, payloadBytes);
    payloadChunks.addFirst(prefix);
    payloadBytes += prefix.length;

    return new OtlpPayload(payloadChunks, payloadBytes, PROTOBUF_CONTENT_TYPE);
  }

  // called once we've processed all spans in a specific scope
  private void completeScope() {
    if (currentSpan != null) {
      completeSpan();
    }

    // add scoped spans message prefix to its nested chunks and promote to payload
    if (scopedBytes > 0) {
      byte[] scopedPrefix = recordScopedSpansMessage(buf, currentScope, scopedBytes);
      payloadChunks.add(scopedPrefix);
      payloadChunks.addAll(scopedChunks);
      payloadBytes += scopedPrefix.length + scopedBytes;
    }

    // reset temporary elements for next scope
    currentScope = null;
    scopedChunks.clear();
    scopedBytes = 0;
  }

  // called once we've processed all span-links in a specific span
  private void completeSpan() {

    // add span message prefix to its nested chunks and promote to scoped
    if (spanBytes > 0) {
      byte[] spanPrefix = recordSpanMessage(buf, currentSpan, metaWriter, spanBytes);
      scopedChunks.add(spanPrefix);
      scopedChunks.addAll(spanChunks);
      scopedBytes += spanPrefix.length + spanBytes;
    }

    // reset temporary elements for next span
    currentSpan = null;
    spanChunks.clear();
    spanBytes = 0;
  }
}
