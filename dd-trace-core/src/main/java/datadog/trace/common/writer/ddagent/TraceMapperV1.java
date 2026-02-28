package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.TracerVersion;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.Config;
import datadog.trace.api.TagMap;
import datadog.trace.api.sampling.SamplingMechanism;
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
  static final int VALUE_TYPE_STRING = 1;
  static final int VALUE_TYPE_BOOLEAN = 2;
  static final int VALUE_TYPE_FLOAT = 3;

  // Span kind OTEL values
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
  private final ByteBuffer header;

  public TraceMapperV1(int bufferSize) {
    this.bufferSize = bufferSize;
    this.stringTable = new StringTable();
    this.spanMetadata = new SpanMetadata();
    this.header = buildHeader();
  }

  public TraceMapperV1() {
    this(5 << 20);
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable) {
    CoreSpan<?> firstSpan = trace.get(0);
    firstSpan.processTagsAndBaggage(spanMetadata);
    Metadata meta = spanMetadata.metadata;

    // encoded fields: 1..7, but skipping #5, as not required by tracers and set by the agent.
    writable.startMap(6);

    // priority = 1, the sampling priority of the trace
    encodeField(writable, 1, meta.samplingPriority()); // TODO double check
    // origin = 2, the optional string origin ("lambda", "rum", etc.) of the trace chunk
    encodeField(writable, 2, firstSpan.getOrigin()); // TODO double check
    // attributes = 3, a collection of key to value pairs common in all `spans`
    encodeAttributes(
        writable, 3, Collections.emptyMap()); // TODO double check if something useful can be added
    // spans = 4, a list of spans in this chunk
    encodeSpans(writable, 4, trace);
    // traceID = 6, the ID of the trace to which all spans in this chunk belong
    encodeField(writable, 6, firstSpan.getTraceId().to128BitBytes());
    // samplingMechanism = 7
    encodeField(writable, 7, parseSamplingMechanism(meta.getTags()));
  }

  private void encodeSpans(Writable writable, int fieldId, List<? extends CoreSpan<?>> spans) {
    int spansCount = spans.size();

    writable.writeInt(fieldId);
    writable.startArray(spansCount);

    for (int i = 0; i < spansCount; i++) {
      final CoreSpan<?> span = spans.get(i);

      span.processTagsAndBaggage(spanMetadata);
      Metadata meta = spanMetadata.metadata;
      TagMap tags = meta.getTags();

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
      encodeAttributes(writable, 9, tags);
      // type = 10, the string type of the service with which this span is associated
      // (example values: web, db, lambda)
      encodeField(writable, 10, span.getType());
      // links = 11, a collection of links to other spans
      // TODO: empty array for now, should be implemented
      writable.writeInt(11);
      writable.startArray(0);
      // events = 12, a collection of events that occurred during this span
      // TODO: empty array for now, should be implemented
      writable.writeInt(12);
      writable.startArray(0);
      // env = 13, the optional string environment of this span
      encodeField(writable, 13, tags.getString(Tags.ENV));
      // version = 14, the optional string version of this span
      encodeField(writable, 14, tags.getString(Tags.VERSION));
      // component = 15, the string component name of this span
      encodeField(writable, 15, tags.getString(Tags.COMPONENT));
      // kind = 16, the SpanKind of this span as defined in the OTEL Specification
      encodeField(writable, 16, getSpanKindValue(tags.getString(Tags.SPAN_KIND)));
    }
  }

  private void encodeAttributes(Writable writable, int fieldId, Map<String, Object> attrs) {
    writable.writeInt(fieldId);
    writable.startArray(attrs.size() * 3); // Array of triplets: (key, type, value).

    for (Map.Entry<String, Object> attr : attrs.entrySet()) {
      String key = attr.getKey();
      writeStreamingString(writable, key);

      Object val = attr.getValue();

      if (val instanceof Number) {
        writable.writeInt(VALUE_TYPE_FLOAT);
        writable.writeDouble(((Number) val).doubleValue());
      } else if (val instanceof Boolean) {
        writable.writeInt(VALUE_TYPE_BOOLEAN);
        writable.writeBoolean((Boolean) val);
      } else {
        if (!(val instanceof String)) {
          log.debug("Not a string value for key: {}, value: {}", key, val);
        }
        writable.writeInt(VALUE_TYPE_STRING);
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

  /**
   * Extracts the numeric sampling mechanism used by the V1 payload field {@code samplingMechanism}
   * from propagation tag {@code _dd.p.dm}.
   *
   * <p>The decision-maker propagation format can be:
   *
   * <ul>
   *   <li>{@code -<mechanism>} (most common, for example {@code -3})
   *   <li>{@code <service-hash>-<mechanism>} (for example {@code 934086a686-3})
   * </ul>
   *
   * <p>V1 payload expects only the numeric mechanism, so we normalize both forms to a positive
   * integer and fall back to {@link SamplingMechanism#DEFAULT} when absent or malformed.
   */
  private int parseSamplingMechanism(TagMap tags) {
    String decisionMaker = tags.getString(KEY_DECISION_MAKER);
    if (decisionMaker == null || decisionMaker.isEmpty()) {
      return SamplingMechanism.DEFAULT;
    }

    // Common format is negative integer ("-3"), but be defensive for "<hash>-3" too.
    try {
      int value = Integer.parseInt(decisionMaker);
      return value < 0 ? -value : value;
    } catch (NumberFormatException ignored) {
      int separator = decisionMaker.lastIndexOf('-');
      if (separator >= 0) {
        try {
          int value = Integer.parseInt(decisionMaker.substring(separator + 1));
          return value < 0 ? -value : value;
        } catch (NumberFormatException ignoredAgain) {
          // Fallback to default.
        }
      }
      return SamplingMechanism.DEFAULT;
    }
  }

  private ByteBuffer buildHeader() {
    GrowableBuffer headerBuffer = new GrowableBuffer(1024);
    Writable headerWriter = new MsgPackWriter(headerBuffer);
    headerWriter.startMap(10);

    Config cfg = Config.get();

    // containerID = 2, the string ID of the container where the tracer is running
    encodeField(headerWriter, 2, ContainerInfo.get().getContainerId());
    // languageName = 3, the string language name of the tracer
    encodeField(headerWriter, 3, "java"); // TODO: check java or jvm?
    // languageVersion = 4, the string language version of the tracer
    encodeField(headerWriter, 4, JavaVirtualMachine.getLangVersion());
    // tracerVersion = 5, the string version of the tracer
    encodeField(headerWriter, 5, TracerVersion.TRACER_VERSION);
    // runtimeID = 6, the V4 string UUID representation of a tracer session
    encodeField(headerWriter, 6, cfg.getRuntimeId());
    // env=7, the optional `env` string tag that set with the tracer
    encodeField(headerWriter, 7, cfg.getEnv());
    // hostname = 8, the optional string hostname of where the tracer is running
    encodeField(headerWriter, 8, cfg.getHostName());
    // appVersion = 9, the optional string `version` tag for the application set in the tracer
    encodeField(headerWriter, 9, cfg.getVersion());
    // attributes = 10, a collection of key to value pairs common in all `chunks`
    encodeAttributes(headerWriter, 10, Collections.emptyMap()); // TODO check useful attrs.
    // chunks = 11, a list of trace `chunks`, value is written by PayloadV1
    headerWriter.writeInt(11);

    stringTable.seal();

    return headerBuffer.slice();
  }

  @Override
  public Payload newPayload() {
    return new PayloadV1(header);
  }

  @Override
  public int messageBufferSize() {
    return bufferSize;
  }

  @Override
  public void reset() {
    stringTable.clear();
  }

  @Override
  public String endpoint() {
    return "v1.0";
  }

  /** String table for streaming string encoding. Index `0` is reserved for empty string. */
  static class StringTable {
    private final Map<String, Integer> header = new HashMap<>();
    private final Map<String, Integer> traces = new HashMap<>();

    private boolean sealed;
    private int nextIndex = 1;

    StringTable() {
      header.put("", 0);
    }

    void seal() {
      sealed = true;
    }

    Integer get(String str) {
      Integer dynamic = traces.get(str);
      return dynamic != null ? dynamic : header.get(str);
    }

    void add(String str) {
      Map<String, Integer> map = sealed ? traces : header;

      Integer idx = map.get(str);

      if (idx == null) {
        map.put(str, nextIndex++);
      }
    }

    void clear() {
      traces.clear();
      nextIndex = header.size();
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
    private final ByteBuffer header;

    private PayloadV1(ByteBuffer header) {
      this.header = header;
    }

    @Override
    public int sizeInBytes() {
      return header.remaining() + msgpackArrayHeaderSize(traceCount()) + body.remaining();
    }

    private void writeBuffer(ByteBuffer buffer, WritableByteChannel channel) throws IOException {
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      writeBuffer(header.slice(), channel);
      writeBuffer(msgpackArrayHeader(traceCount()), channel);
      writeBuffer(body, channel);
    }

    @Override
    public RequestBody toRequest() {
      return msgpackRequestBodyOf(
          Arrays.asList(header.slice(), msgpackArrayHeader(traceCount()), body));
    }
  }
}
