package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_PARTIAL_VERSION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_TOP_LEVEL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_WAS_LONG_RUNNING;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
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
import static datadog.trace.core.otlp.common.OtlpCommonProto.sizeVarInt;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI32;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI64;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeInstrumentationScope;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeString;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeVarInt;
import static datadog.trace.core.otlp.common.OtlpTraceFlags.NO_TRACE_FLAGS;
import static datadog.trace.core.otlp.common.OtlpTraceFlags.REMOTE_TRACE_FLAG;
import static datadog.trace.core.otlp.common.OtlpTraceFlags.SAMPLED_TRACE_FLAG;
import static datadog.trace.core.otlp.trace.OtlpSpanKind.spanKind;
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
import datadog.trace.core.otlp.common.OtlpProtoBuffer;
import datadog.trace.core.propagation.PropagationTags;

/** Provides optimized writers for OpenTelemetry's "trace.proto" wire protocol. */
public final class OtlpTraceProto {

  private static final UTF8BytesString SERVICE_NAME = UTF8BytesString.create("service.name");
  private static final UTF8BytesString RESOURCE_NAME = UTF8BytesString.create("resource.name");
  private static final UTF8BytesString OPERATION_NAME = UTF8BytesString.create("operation.name");
  private static final UTF8BytesString SPAN_TYPE = UTF8BytesString.create("span.type");

  private OtlpTraceProto() {}

  /** Records a scoped spans message after its nested span messages have been recorded. */
  public static int recordScopedSpansMessage(
      GrowableBuffer buf,
      OtelInstrumentationScope scope,
      int nestedSpanBytes,
      OtlpProtoBuffer protobuf) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeInstrumentationScope(buf, scope);
    if (scope.getSchemaUrl() != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, scope.getSchemaUrl().getUtf8Bytes());
    }

    return protobuf.recordMessage(buf, 2, nestedSpanBytes);
  }

  /** Records a span message after its nested span-link messages have been recorded. */
  public static int recordSpanMessage(
      GrowableBuffer buf,
      DDSpan span,
      MetaWriter metaWriter,
      int nestedSpanLinkBytes,
      OtlpProtoBuffer protobuf) {
    PropagationTags propagationTags = span.spanContext().getPropagationTags();

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeTraceId(buf, span.getTraceId());

    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeSpanId(buf, span.getSpanId());

    String tracestate = propagationTags.getW3CTracestate();
    if (tracestate != null) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, tracestate);
    }

    if (span.getParentId() != 0) {
      writeTag(buf, 4, LEN_WIRE_TYPE);
      writeSpanId(buf, span.getParentId());
    }

    int traceFlags = NO_TRACE_FLAGS;
    if (span.samplingPriority() > 0) {
      traceFlags |= SAMPLED_TRACE_FLAG;
    }
    if (span.spanContext().isRemote()) {
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
    writeVarInt(buf, spanKind(span.spanContext().getSpanKindString()));

    writeTag(buf, 7, I64_WIRE_TYPE);
    writeI64(buf, span.getStartTime());

    writeTag(buf, 8, I64_WIRE_TYPE);
    writeI64(buf, span.getStartTime() + PendingTrace.getDurationNano(span));

    if (!Config.get().getServiceName().equals(span.getServiceName())) {
      writeSpanTag(buf, SERVICE_NAME, span.getServiceName());
    }
    writeSpanTag(buf, RESOURCE_NAME, span.getResourceName());
    writeSpanTag(buf, OPERATION_NAME, span.getOperationName());
    if (span.getSpanType() != null) {
      writeSpanTag(buf, SPAN_TYPE, span.getSpanType());
    }

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

    return protobuf.recordMessage(buf, 2, nestedSpanLinkBytes);
  }

  /** Records a span-link message. */
  public static int recordSpanLinkMessage(
      GrowableBuffer buf, AgentSpanLink spanLink, OtlpProtoBuffer protobuf) {

    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeTraceId(buf, spanLink.traceId());

    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeSpanId(buf, spanLink.spanId());

    if (!spanLink.traceState().isEmpty()) {
      writeTag(buf, 3, LEN_WIRE_TYPE);
      writeString(buf, spanLink.traceState());
    }

    spanLink
        .attributes()
        .asMap()
        .forEach(
            (key, value) -> {
              writeTag(buf, 4, LEN_WIRE_TYPE);
              writeAttribute(buf, STRING_ATTRIBUTE, key, value);
            });

    writeTag(buf, 6, I32_WIRE_TYPE);
    writeI32(buf, spanLink.traceFlags() & 0xff);

    return protobuf.recordMessage(buf, 13);
  }

  public static void writeTraceId(StreamingBuffer buf, DDTraceId traceId) {
    writeVarInt(buf, 16);
    buf.putLong(traceId.toHighOrderLong());
    buf.putLong(traceId.toLong());
  }

  public static void writeSpanId(StreamingBuffer buf, long spanId) {
    writeVarInt(buf, 8);
    buf.putLong(spanId);
  }

  private static void writeSpanTag(StreamingBuffer buf, TagMap.EntryReader tagEntry) {
    writeTag(buf, 9, LEN_WIRE_TYPE);
    switch (tagEntry.type()) {
      case TagMap.EntryReader.BOOLEAN:
        writeAttribute(buf, BOOLEAN_ATTRIBUTE, tagEntry.tag(), tagEntry.objectValue());
        break;
      case TagMap.EntryReader.INT:
      case TagMap.EntryReader.LONG:
        writeAttribute(buf, LONG_ATTRIBUTE, tagEntry.tag(), tagEntry.objectValue());
        break;
      case TagMap.EntryReader.FLOAT:
      case TagMap.EntryReader.DOUBLE:
        writeAttribute(buf, DOUBLE_ATTRIBUTE, tagEntry.tag(), tagEntry.objectValue());
        break;
      default:
        writeAttribute(buf, STRING_ATTRIBUTE, tagEntry.tag(), tagEntry.stringValue());
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

  public static class MetaWriter implements MetadataConsumer {
    private final StreamingBuffer buf;

    private boolean includeProcessTags;
    private boolean includeSamplingTags;

    public MetaWriter(StreamingBuffer buf) {
      this.buf = buf;
    }

    /** Call this to ensure process tags are written out for the next span. */
    public void includeProcessTags() {
      includeProcessTags = true;
    }

    /** Call this to ensure sampling tags are written out for the next span. */
    public void includeSamplingTags() {
      includeSamplingTags = true;
    }

    @Override
    public void accept(Metadata metadata) {
      if ((includeSamplingTags || metadata.topLevel()) && metadata.hasSamplingPriority()) {
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
      if (includeProcessTags && metadata.processTags() != null) {
        writeSpanTag(buf, PROCESS_TAGS_KEY, metadata.processTags());
      }

      metadata.getTags().forEach(buf, OtlpTraceProto::writeSpanTag);

      // reset for next span
      includeProcessTags = false;
      includeSamplingTags = false;
    }
  }
}
