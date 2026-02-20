package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.communication.serialization.Writable;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.Payload;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.core.PendingTrace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Efficient Trace Payload Protocol V1. */
@SuppressWarnings("SameParameterValue")
public final class TraceMapperV1 implements TraceMapper {
  private static final Logger log = LoggerFactory.getLogger(TraceMapperV1.class);

  // Attribute value types (from V1 spec)
  static final int STRING_VALUE_TYPE = 1;
  static final int BOOL_VALUE_TYPE = 2;
  static final int FLOAT_VALUE_TYPE = 3;
  static final int INT_VALUE_TYPE = 4;
  static final int BYTES_VALUE_TYPE = 5;
  static final int ARRAY_VALUE_TYPE = 6;
  static final int KEY_VALUE_LIST_TYPE = 7;

  // Span kind OTEL values
  static final int SPAN_KIND_UNSPECIFIED = 0;
  static final int SPAN_KIND_INTERNAL = 1;
  static final int SPAN_KIND_SERVER = 2;
  static final int SPAN_KIND_CLIENT = 3;
  static final int SPAN_KIND_PRODUCER = 4;
  static final int SPAN_KIND_CONSUMER = 5;

  // Decision maker tag key
  private static final String KEY_DECISION_MAKER = "_dd.p.dm";

  private final int bufferSize;
  private final StringTable stringTable;
  private final SpanMetadata spanMetadata;
  private boolean firstSpanWritten;

  public TraceMapperV1(int bufferSize) {
    this.bufferSize = bufferSize;
    this.stringTable = new StringTable();
    this.spanMetadata = new SpanMetadata();
  }

  public TraceMapperV1() {
    this(5 << 20);
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable) {
    writable.startMap(5); // trace chunk has 7 attributes

    CoreSpan<?> firstSpan = trace.get(0);
    firstSpan.processTagsAndBaggage(spanMetadata);
    Metadata meta = spanMetadata.metadata;

    // priority = 1, the sampling priority of the trace
    encodeField(writable, 1, meta.samplingPriority()); // TODO check
    // origin = 2, the optional string origin ("lambda", "rum", etc.) of the trace chunk
    encodeField(writable, 2, firstSpan.getOrigin()); // TODO implement
    // attributes = 3, a collection of key to value pairs common in all `spans`
    encodeAttributes(writable, 3, Collections.emptyMap()); // TODO implement
    // spans = 4, a list of spans in this chunk
    encodeSpans(writable, 4, trace);

    // // droppedTrace = 5, whether the trace only contains analyzed spans
    // // (not required by tracers and set by the agent)
    // encodeField(writable, 5, false); // TODO: double check

    // traceID = 6, the ID of the trace to which all spans in this chunk belong
    encodeField(writable, 6, firstSpan.getTraceId().to128BitBytes()); // TODO check

    // // samplingMechanism = 7, the optional sampling mechanism
    // // (previously span tag _dd.p.dm, but now it’s just the int)
    // encodeField(writable, 7, 0); // TODO implement
  }

  private void encodeSpans(Writable writable, int fieldId, List<? extends CoreSpan<?>> spans) {
    int spansCount = spans.size();

    writable.writeInt(fieldId); // Protocol fieldId
    writable.startArray(spansCount); // Number of spans

    for (int i = 0; i < spansCount; i++) {
      final CoreSpan<?> span = spans.get(i);

      span.processTagsAndBaggage(spanMetadata);
      Metadata meta = spanMetadata.metadata;

      // Span has 16 fields
      writable.startMap(16);

      // service = 1, the string name of the service that this span is associated with
      encodeField(writable, 1, span.getServiceName());
      // name = 2, the string operation name of this span
      encodeField(writable, 2, span.getOperationName());
      // resource = 3, the string resource name of this span,
      // sometimes called endpoint for web spans
      encodeField(writable, 3, span.getResourceName());
      // spanID = 4, the ID of this span
      encodeField(writable, 4, span.getSpanId());
      // parentID = 5, the ID of this span's parent, or zero if there is no parent
      encodeField(writable, 5, span.getParentId());
      // start = 6, the number of nanoseconds from the Unix epoch to the start of this span
      encodeField(writable, 6, span.getStartTime());
      // duration = 7, the time length of this span in nanoseconds
      encodeField(writable, 7, PendingTrace.getDurationNano(span));
      // error = 8, if there is an error associated with this span
      encodeField(writable, 8, span.getError() != 0);
      // attributes = 9, a collection of string key to value pairs on the span
      encodeAttributes(writable, 9, meta.getTags()); // TODO check and implement
      // type = 10, the string type of the service with which this span is associated
      // (example values: web, db, lambda)
      encodeField(writable, 10, span.getType());
      // links = 11, a collection of links to other spans
      // (empty array for now - could be extended)
      writable.writeInt(11);
      writable.startArray(0);
      // events = 12, a collection of events that occurred during this span
      // (empty array for now - could be extended)
      writable.writeInt(12);
      writable.startArray(0);
      // env = 13, the optional string environment of this span
      encodeField(writable, 13, "todo"); // TODO implement
      // version = 14, the optional string version of this span
      encodeField(writable, 14, "todo"); // TODO implement
      // component = 15, the string component name of this span
      encodeField(writable, 15, "todo"); // TODO implement
      // kind = 16, the SpanKind of this span as defined in the OTEL Specification
      encodeField(writable, 16, getSpanKindValue(span.getType())); // TODO implement

      // // Fields 13-16: promoted fields (env, version, component, spanKind)
      // span.processTagsAndBaggage(
      //     metaWriter
      //         .withWritable(writable)
      //         .forSpan(i == 0, i == trace.size() - 1, !firstSpanWritten));
      //
      // firstSpanWritten = true;
    }
  }

  // TODO: For now just write `test` attr with value `26` so far.
  private void encodeAttributes(Writable writable, int fieldId, Map<String, Object> attrs) {
    writable.writeInt(fieldId);
    writable.startArray(attrs.size() * 3);

    for (Map.Entry<String, Object> attr : attrs.entrySet()) {
      String key = attr.getKey();
      writeStreamingString(writable, key);

      Object val = attr.getValue();

      if (val instanceof Number) {
        writable.writeInt(FLOAT_VALUE_TYPE);
        writable.writeDouble(((Number) val).doubleValue());
      } else if (val instanceof Boolean) {
        writable.writeInt(BOOL_VALUE_TYPE);
        writable.writeBoolean((Boolean) val);
      } else {
        if (!(val instanceof String)) {
          log.debug("Not a string value for key: {}, value: {}", key, val);
        }
        writable.writeInt(STRING_VALUE_TYPE);
        writeStreamingString(
            writable, val == null ? "" : val.toString()); // TODO check and implement
      }
    }
  }

  private void encodeField(Writable writable, int fieldId, boolean value) {
    writable.writeInt(fieldId);
    writable.writeBoolean(value);
  }

  private void encodeField(Writable writable, int fieldId, long value) {
    writable.writeInt(fieldId);
    writable.writeLong(value);
  }

  private void encodeField(Writable writable, int fieldId, CharSequence value) {
    writable.writeInt(fieldId);
    writeStreamingString(writable, value);
  }

  private void encodeField(Writable writable, int fieldId, byte[] value) {
    writable.writeInt(fieldId);
    writable.writeBinary(value);
  }

  /** Writes a string using the streaming string table encoding. */
  private void writeStreamingString(Writable writable, CharSequence value) {
    String str = value == null ? "" : value.toString();
    Integer index = stringTable.get(str);
    if (index != null) {
      // String already in table, write index
      writable.writeInt(index);
    } else {
      // New string, write it directly and add to table
      writable.writeString(str, null);
      stringTable.add(str);
    }
  }

  /** Converts a span kind string to its OTEL uint32 value. */
  static int getSpanKindValue(CharSequence spanKind) {
    if (spanKind == null) {
      return SPAN_KIND_INTERNAL;
    }

    switch (spanKind.toString()) {
      case Tags.SPAN_KIND_INTERNAL:
        return SPAN_KIND_INTERNAL;
      case Tags.SPAN_KIND_SERVER:
        return SPAN_KIND_SERVER;
      case Tags.SPAN_KIND_CLIENT:
        return SPAN_KIND_CLIENT;
      case Tags.SPAN_KIND_PRODUCER:
        return SPAN_KIND_PRODUCER;
      case Tags.SPAN_KIND_CONSUMER:
        return SPAN_KIND_CONSUMER;
      default:
        return SPAN_KIND_INTERNAL;
    }
  }

  @Override
  public Payload newPayload() {

    // TODO
    // // TODO: probably this can be optimized by caching this data once.
    // Config cfg = Config.get();
    //
    // writable.startMap(10);
    //
    // // containerID = 2, the string ID of the container where the tracer is running
    // encodeField(writable, 2, ContainerInfo.get().getContainerId());
    // // languageName = 3, the string language name of the tracer
    // encodeField(writable, 3, "java"); // TODO: check java or jvm?
    // // languageVersion = 4, the string language version of the tracer
    // encodeField(writable, 4, JavaVirtualMachine.getLangVersion());
    // // tracerVersion = 5, the string version of the tracer
    // encodeField(writable, 5, TracerVersion.TRACER_VERSION);
    // // runtimeID = 6, the V4 string UUID representation of a tracer session
    // encodeField(writable, 6, cfg.getRuntimeId());
    // // env=7, the optional `env` string tag that set with the tracer
    // encodeField(writable, 7, cfg.getEnv());
    // // hostname = 8, the optional string hostname of where the tracer is running
    // encodeField(writable, 8, cfg.getHostName());
    // // appVersion = 9, the optional string `version` tag for the application set in the tracer
    // encodeField(writable, 9, cfg.getVersion());
    // // attributes = 10, a collection of key to value pairs common in all `chunks`
    // encodeAttributes(writable, 10); // TODO implement
    // // chunks = 11, a list of trace `chunks`
    // encodeTraceChunks(writable, 11, trace);
    //

    return new PayloadV1();
  }

  @Override
  public int messageBufferSize() {
    return bufferSize;
  }

  @Override
  public void reset() {
    stringTable.clear();
    firstSpanWritten = false;
  }

  @Override
  public String endpoint() {
    return "v1.0";
  }

  /** String table for streaming string encoding. Index `0` is reserved for empty string. */
  static class StringTable {
    private final Map<String, Integer> indices = new HashMap<>();
    private int nextIndex = 1;

    StringTable() {
      indices.put("", 0);
    }

    Integer get(String str) {
      return indices.get(str);
    }

    void add(String str) {
      if (!indices.containsKey(str)) {
        indices.put(str, nextIndex++);
      }
    }

    void clear() {
      indices.clear();
      indices.put("", 0);
      nextIndex = 1;
    }

    int size() {
      return nextIndex;
    }
  }

  private static class SpanMetadata implements MetadataConsumer {
    private Metadata metadata;

    @Override
    public void accept(Metadata metadata) {
      this.metadata = metadata;
    }
  }

  /** Payload implementation for V1.0 format. */
  private static class PayloadV1 extends Payload {
    @Override
    public int sizeInBytes() {
      // Map header (for TracerPayload) + array header (with number of TraceChunks) + body
      return msgpackMapHeaderSize(1) + msgpackArrayHeaderSize(traceCount()) + body.remaining();
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      // Write the tracer payload map with chunks field (field 11)
      // TODO write just the chunks array wrapped in a minimal payload structure
      // Add all other fields later.
      ByteBuffer mapHeader = msgpackMapHeader(1);
      while (mapHeader.hasRemaining()) {
        channel.write(mapHeader);
      }

      // Write field ID 11 (chunks).
      ByteBuffer fieldId = ByteBuffer.allocate(1).put(0, (byte) 11);
      while (fieldId.hasRemaining()) {
        channel.write(fieldId);
      }

      // Write array header for traces count.
      ByteBuffer header = msgpackArrayHeader(traceCount());
      while (header.hasRemaining()) {
        channel.write(header);
      }

      // Write traces.
      while (body.hasRemaining()) {
        channel.write(body);
      }
    }

    @Override
    public RequestBody toRequest() {
      return msgpackRequestBodyOf(
          Arrays.asList(
              msgpackMapHeader(1),
              ByteBuffer.allocate(1).put(0, (byte) 11),
              msgpackArrayHeader(traceCount()),
              body));
    }
  }
}
