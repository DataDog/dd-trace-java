package datadog.trace.core.otlp.trace;

import static datadog.trace.core.otlp.common.OtlpResourceProto.buildTraceResourceMessage;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordScopedSpansMessage;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordSpanLinkMessage;
import static datadog.trace.core.otlp.trace.OtlpTraceProto.recordSpanMessage;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.api.TagMap;
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
 * to the resource chunks. Once the resource is complete we add the enclosing resource spans
 * message, carrying the global tags of its traces, to the payload. A trace whose global tags differ
 * (after a remote config tag change) completes the current resource and starts a new one.
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
  private int resourceBytes;
  private int scopedBytes;
  private int spanBytes;

  private OtelInstrumentationScope currentScope;
  private DDSpan currentSpan;

  // global tags of the trace being added
  private TagMap traceGlobalTags;

  // resources for the last two global tags seen, see CachedResource
  private CachedResource currentResource;
  private CachedResource previousResource;

  /** Adds the given trace spans to the collector. */
  @Override
  public void addTrace(List<? extends CoreSpan<?>> spans) {
    if (!payloadStarted) {
      start();
      payloadStarted = true;
    }

    traceGlobalTags = globalTagsOf(spans);
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
  }

  /** Cleanup elements used to collect trace data. */
  private void stop() {
    payloadStarted = false;
    payloadGlobalTags = null;
    traceGlobalTags = null;

    buf.reset();
    protobuf.reset();

    payloadBytes = 0;
    resourceBytes = 0;
    scopedBytes = 0;
    spanBytes = 0;

    currentScope = null;
    currentSpan = null;
  }

  private void visitResource(TagMap globalTags) {
    if (currentScope != null) {
      completeResource();
    }
    payloadGlobalTags = globalTags;

    // for now put all spans under the default scope
    visitScopedSpans(DEFAULT_TRACE_SCOPE);
  }

  private void visitScopedSpans(OtelInstrumentationScope scope) {
    if (currentScope != null) {
      completeScope();
    }
    currentScope = scope;
  }

  private void visitSpan(CoreSpan<?> span) {
    if (!shouldExport(span)) {
      return;
    }
    if (currentScope == null || traceGlobalTags != payloadGlobalTags) {
      // spans with different global tags need a resource of their own
      visitResource(traceGlobalTags);
    }
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

  private void visitSpanLink(AgentSpanLink spanLink) {
    spanBytes += recordSpanLinkMessage(buf, spanLink, protobuf);
  }

  // called once we've processed all resources and span messages
  private OtlpPayload completePayload() {
    if (currentScope != null) {
      completeResource();
    }

    if (payloadBytes == 0) {
      return OtlpPayload.EMPTY;
    }

    return protobuf.toPayload();
  }

  // called once we've processed all spans with the same global tags
  private void completeResource() {
    completeScope();

    // prepend the resource chunk, which carries the global tags
    resourceBytes += protobuf.recordMessage(resourceMessage());

    // then prepend the total length of the resource's chunks
    payloadBytes += protobuf.recordMessage(buf, 1, resourceBytes);

    // reset temporary elements for next resource
    resourceBytes = 0;
  }

  private byte[] resourceMessage() {
    if (currentResource == null || currentResource.globalTags != payloadGlobalTags) {
      CachedResource replaced = currentResource;
      currentResource =
          previousResource != null && previousResource.globalTags == payloadGlobalTags
              ? previousResource
              : new CachedResource(payloadGlobalTags, buildTraceResourceMessage(payloadGlobalTags));
      previousResource = replaced;
    }
    return currentResource.message;
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
      resourceBytes += recordScopedSpansMessage(buf, currentScope, scopedBytes, protobuf);
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

  /**
   * A resource built for one global tags map, cached so it's only rebuilt when the tags change.
   *
   * <p>A trace takes its global tags from the config snapshot when it starts, so after a remote
   * config tag change, traces started before the change (such as long-lived traces still sending
   * partial flushes) keep arriving with the old tags, interleaved with new traces. The collector
   * keeps the current and the previous resource, so switching back and forth between the two swaps
   * them instead of rebuilding each time.
   */
  private static final class CachedResource {
    final TagMap globalTags;
    final byte[] message;

    CachedResource(TagMap globalTags, byte[] message) {
      this.globalTags = globalTags;
      this.message = message;
    }
  }
}
