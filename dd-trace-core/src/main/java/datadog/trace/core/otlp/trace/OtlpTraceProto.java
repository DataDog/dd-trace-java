package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_PARTIAL_VERSION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_TOP_LEVEL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_WAS_LONG_RUNNING;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING;
import static datadog.trace.common.writer.RemoteMapper.HTTP_STATUS;
import static datadog.trace.common.writer.ddagent.TraceMapper.ORIGIN_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.PROCESS_TAGS_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.SAMPLING_PRIORITY_KEY;
import static datadog.trace.common.writer.ddagent.TraceMapper.THREAD_ID;
import static datadog.trace.common.writer.ddagent.TraceMapper.THREAD_NAME;
import static datadog.trace.core.otlp.common.OtlpCommonProto.I32_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.I64_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.VARINT_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.recordMessage;
import static datadog.trace.core.otlp.common.OtlpCommonProto.sizeVarInt;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI32;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI64;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeInstrumentationScope;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeString;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeVarInt;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.StreamingBuffer;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.core.DDSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.propagation.PropagationTags;

/** Provides optimized writers for OpenTelemetry's "trace.proto" wire protocol. */
public final class OtlpTraceProto {

  static final int NO_TRACE_FLAGS = 0x00000000;
  static final int SAMPLED_TRACE_FLAG = 0x00000001;
  static final int REMOTE_TRACE_FLAG = 0x00000300;

  private OtlpTraceProto() {}

  /**
   * Records the first part of a scoped spans message where we know its nested span messages will
   * follow in one or more byte-arrays that add up to the given number of remaining bytes.
   */
  public static byte[] recordScopedSpansMessage(
      GrowableBuffer buf, OtelInstrumentationScope scope, int remainingBytes) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeInstrumentationScope(buf, scope);
    if (scope.getSchemaUrl() != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, scope.getSchemaUrl().getUtf8Bytes());
    }

    return recordMessage(buf, 2, remainingBytes);
  }

  /**
   * Records the first part of a span message where we know its nested span-links will follow in one
   * or more byte-arrays that add up to the given number of remaining bytes.
   */
  public static byte[] recordSpanMessage(
      GrowableBuffer buf, DDSpan span, MetaWriter metaWriter, int remainingBytes) {
    PropagationTags propagationTags = span.context().getPropagationTags();

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeTraceId(buf, span.getTraceId());

    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeSpanId(buf, span.getSpanId());

    writeTag(buf, 3, LEN_WIRE_TYPE);
    writeString(buf, propagationTags.getW3CTracestate());

    if (span.getParentId() != 0) {
      writeTag(buf, 4, LEN_WIRE_TYPE);
      writeSpanId(buf, span.getParentId());
    }

    int traceFlags = NO_TRACE_FLAGS;
    if (span.samplingPriority() > 0) {
      traceFlags |= SAMPLED_TRACE_FLAG;
    }
    if (span.context().isRemote()) {
      traceFlags |= REMOTE_TRACE_FLAG;
    }
    if (traceFlags != NO_TRACE_FLAGS) {
      writeTag(buf, 16, I32_WIRE_TYPE);
      writeI32(buf, traceFlags);
    }

    writeTag(buf, 5, LEN_WIRE_TYPE);
    CharSequence spanName = span.getResourceName();
    if (spanName instanceof UTF8BytesString) {
      writeString(buf, ((UTF8BytesString) spanName).getUtf8Bytes());
    } else {
      writeString(buf, spanName.toString());
    }

    writeTag(buf, 6, VARINT_WIRE_TYPE);
    writeVarInt(buf, spanKind((CharSequence) span.getTag(SPAN_KIND)));

    writeTag(buf, 7, I64_WIRE_TYPE);
    writeI64(buf, span.getStartTime());

    writeTag(buf, 8, I64_WIRE_TYPE);
    writeI64(buf, span.getStartTime() + PendingTrace.getDurationNano(span));

    if (!Config.get().getServiceName().equalsIgnoreCase(span.getServiceName())) {
      writeTag(buf, 9, LEN_WIRE_TYPE);
      writeAttribute(buf, STRING, "service.name", span.getServiceName());
    }
    writeTag(buf, 9, LEN_WIRE_TYPE);
    writeAttribute(buf, STRING, "resource.name", span.getResourceName());
    writeTag(buf, 9, LEN_WIRE_TYPE);
    writeAttribute(buf, STRING, "operation.name", span.getOperationName());
    writeTag(buf, 9, LEN_WIRE_TYPE);
    writeAttribute(buf, STRING, "span.type", span.getSpanType());

    span.processTagsAndBaggage(metaWriter);

    if (span.isError()) {
      int stateSize = 2;
      byte[] errorUtf8 = null;
      Object errorMessage = span.getTag(DDTags.ERROR_MSG);
      if (errorMessage instanceof String) {
        errorUtf8 = ((String) errorMessage).getBytes(UTF_8);
        stateSize += 1 + sizeVarInt(errorUtf8.length) + errorUtf8.length;
      }
      writeTag(buf, 15, LEN_WIRE_TYPE);
      writeVarInt(buf, stateSize);
      if (errorUtf8 != null) {
        writeTag(buf, 2, LEN_WIRE_TYPE);
        writeString(buf, errorUtf8);
      }
      writeTag(buf, 3, VARINT_WIRE_TYPE);
      writeVarInt(buf, 2);
    }

    return recordMessage(buf, 2, remainingBytes);
  }

  /** Completes recording of a span-link message and packs it into its own byte-array. */
  public static byte[] recordSpanLinkMessage(GrowableBuffer buf, AgentSpanLink spanLink) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeTraceId(buf, spanLink.traceId());

    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeSpanId(buf, spanLink.spanId());

    writeTag(buf, 3, LEN_WIRE_TYPE);
    writeString(buf, spanLink.traceState());

    spanLink
        .attributes()
        .asMap()
        .forEach(
            (key, value) -> {
              writeTag(buf, 4, LEN_WIRE_TYPE);
              writeAttribute(buf, STRING, key, value);
            });

    writeTag(buf, 6, I32_WIRE_TYPE);
    writeI32(buf, spanLink.traceFlags());

    return recordMessage(buf, 13);
  }

  public static void writeTraceId(StreamingBuffer buf, DDTraceId traceId) {
    writeVarInt(buf, 16);
    writeI64(buf, traceId.toLong());
    writeI64(buf, traceId.toHighOrderLong());
  }

  public static void writeSpanId(StreamingBuffer buf, long spanId) {
    writeVarInt(buf, 8);
    writeI64(buf, spanId);
  }

  private static void writeSpanTag(StreamingBuffer buf, TagMap.EntryReader tagEntry) {
    writeTag(buf, 9, LEN_WIRE_TYPE);
    switch (tagEntry.type()) {
      case TagMap.EntryReader.BOOLEAN:
        writeAttribute(buf, BOOLEAN, tagEntry.tag(), tagEntry.objectValue());
        break;
      case TagMap.EntryReader.INT:
      case TagMap.EntryReader.LONG:
        writeAttribute(buf, LONG, tagEntry.tag(), tagEntry.objectValue());
        break;
      case TagMap.EntryReader.FLOAT:
      case TagMap.EntryReader.DOUBLE:
        writeAttribute(buf, DOUBLE, tagEntry.tag(), tagEntry.objectValue());
        break;
      default:
        writeAttribute(buf, STRING, tagEntry.tag(), tagEntry.stringValue());
    }
  }

  private static void writeSpanTag(
      StreamingBuffer buf, UTF8BytesString key, UTF8BytesString value) {
    writeTag(buf, 9, LEN_WIRE_TYPE);
    writeAttribute(buf, key, value);
  }

  private static void writeSpanTag(StreamingBuffer buf, UTF8BytesString key, CharSequence value) {
    writeTag(buf, 9, LEN_WIRE_TYPE);
    if (value instanceof UTF8BytesString) {
      writeAttribute(buf, key, (UTF8BytesString) value);
    } else {
      writeAttribute(buf, key, value.toString());
    }
  }

  private static void writeSpanTag(StreamingBuffer buf, UTF8BytesString key, long value) {
    writeTag(buf, 9, LEN_WIRE_TYPE);
    writeAttribute(buf, key, value);
  }

  private static int spanKind(CharSequence spanKind) {
    if (SPAN_KIND_SERVER.contentEquals(spanKind)) {
      return 2; // SERVER
    } else if (SPAN_KIND_CLIENT.contentEquals(spanKind)) {
      return 3; // CLIENT
    } else if (SPAN_KIND_PRODUCER.contentEquals(spanKind)) {
      return 4; // PRODUCER
    } else if (SPAN_KIND_CONSUMER.contentEquals(spanKind)) {
      return 5; // CONSUMER
    } else {
      return 1; // INTERNAL
    }
  }

  public static class MetaWriter implements MetadataConsumer {
    private final StreamingBuffer buf;
    private boolean firstSpan = true;

    public MetaWriter(StreamingBuffer buf) {
      this.buf = buf;
    }

    public void reset() {
      firstSpan = true;
    }

    @Override
    public void accept(Metadata metadata) {
      if ((firstSpan || metadata.topLevel()) && metadata.hasSamplingPriority()) {
        writeSpanTag(buf, SAMPLING_PRIORITY_KEY, metadata.samplingPriority());
      }
      if (metadata.measured()) {
        writeSpanTag(buf, DD_MEASURED, 1);
      }
      if (metadata.topLevel()) {
        writeSpanTag(buf, DD_TOP_LEVEL, 1);
      }

      if (metadata.longRunningVersion() != 0) {
        if (metadata.longRunningVersion() > 0) {
          writeSpanTag(buf, DD_PARTIAL_VERSION, metadata.longRunningVersion());
        } else {
          writeSpanTag(buf, DD_WAS_LONG_RUNNING, 1);
        }
      }

      writeSpanTag(buf, THREAD_ID, metadata.getThreadId());
      writeSpanTag(buf, THREAD_NAME, metadata.getThreadName());
      if (metadata.getHttpStatusCode() != null) {
        writeSpanTag(buf, HTTP_STATUS, metadata.getHttpStatusCode());
      }
      if (metadata.getOrigin() != null) {
        writeSpanTag(buf, ORIGIN_KEY, metadata.getOrigin());
      }
      if (firstSpan && metadata.processTags() != null) {
        writeSpanTag(buf, PROCESS_TAGS_KEY, metadata.processTags());
      }

      metadata.getTags().forEach(tagEntry -> writeSpanTag(buf, tagEntry));

      firstSpan = false;
    }
  }
}
