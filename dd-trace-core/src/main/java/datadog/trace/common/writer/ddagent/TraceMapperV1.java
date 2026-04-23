package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.TracerVersion;
import datadog.communication.serialization.Codec;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
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
  static final int VALUE_TYPE_INT = 4;
  static final int VALUE_TYPE_BYTES = 5;
  static final int VALUE_TYPE_ARRAY = 6;

  // Span kind OTEL values
  static final int SPAN_KIND_UNSPECIFIED = 0;
  static final int SPAN_KIND_INTERNAL = 1;
  static final int SPAN_KIND_SERVER = 2;
  static final int SPAN_KIND_CLIENT = 3;
  static final int SPAN_KIND_PRODUCER = 4;
  static final int SPAN_KIND_CONSUMER = 5;

  // Decision maker tag key
  private static final String KEY_DECISION_MAKER = "_dd.p.dm";
  private static final String HTTP_STATUS = "http.status_code";

  private final int bufferSize;
  private final StringTable stringTable;
  private final SpanMetadata spanMetadata;
  private final GrowableBuffer metaStructBuffer;
  private final MsgPackWriter metaStructWriter;
  private final ByteBuffer header;

  public TraceMapperV1(int bufferSize) {
    this.bufferSize = bufferSize;
    this.stringTable = new StringTable();
    this.spanMetadata = new SpanMetadata();
    this.metaStructBuffer = new GrowableBuffer(1 << 10);
    this.metaStructWriter = new MsgPackWriter(Codec.INSTANCE, metaStructBuffer);
    this.header = buildHeader();
  }

  public TraceMapperV1() {
    this(5 << 20);
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable) {
    if (trace.isEmpty()) {
      // Do nothing if no spans in the trace
      return;
    }

    CoreSpan<?> firstSpan = trace.get(0);
    firstSpan.processTagsAndBaggage(spanMetadata, false, false);
    Metadata firstSpanMeta = spanMetadata.metadata;

    // encoded fields: 1..7, but skipping #5, as not required by tracers and set by the agent.
    writable.startMap(6);

    // priority = 1, the sampling priority of the trace, int32
    encodeInt(writable, 1, firstSpanMeta.samplingPriority());
    // origin = 2, the optional string origin ("lambda", "rum", etc.) of the trace chunk
    encodeString(writable, 2, firstSpan.getOrigin());
    // attributes = 3, a collection of key to value pairs common in all `spans`
    encodeAttributes(writable, 3, buildChunkAttributes(trace));
    // spans = 4, a list of spans in this chunk
    encodeSpans(writable, 4, trace);
    // traceID = 6, the ID of the trace to which all spans in this chunk belong
    encodeTraceId(writable, 6, firstSpan.getTraceId());
    // samplingMechanism = 7, uint32
    encodeInt(writable, 7, parseSamplingMechanism(firstSpanMeta.getTags()));
  }

  private Map<String, Object> buildChunkAttributes(List<? extends CoreSpan<?>> trace) {
    CoreSpan<?> localRoot = trace.get(0).getLocalRootSpan();
    CharSequence service = localRoot == null ? null : localRoot.getServiceName();
    if (service == null) {
      return emptyMap();
    }
    return singletonMap("service", service);
  }

  private void encodeSpans(Writable writable, int fieldId, List<? extends CoreSpan<?>> spans) {
    int spansCount = spans.size();

    writable.writeInt(fieldId);
    writable.startArray(spansCount);

    // spanMetadata will already have data from first span.
    Metadata meta = spanMetadata.metadata;
    for (CoreSpan<?> span : spans) {
      if (meta == null) {
        span.processTagsAndBaggage(spanMetadata, false, false);
        meta = spanMetadata.metadata;
      }
      TagMap tags = meta.getTags();
      Map<String, Object> metaStruct = span.getMetaStruct();

      // Span has 16 fields
      writable.startMap(16);

      // service = 1, the string name of the service that this span is associated with
      encodeString(writable, 1, span.getServiceName());
      // name = 2, the string operation name of this span
      encodeString(writable, 2, span.getOperationName());
      // resource = 3, the string resource name of this span,
      // sometimes called endpoint for web spans
      encodeString(writable, 3, span.getResourceName());
      // spanID = 4, the ID of this span
      encodeLongUnsigned(writable, 4, span.getSpanId());
      // parentID = 5, the ID of this span's parent, or zero if there is no parent
      encodeLongUnsigned(writable, 5, span.getParentId());
      // start = 6, the number of nanoseconds from the Unix epoch to the start of this span
      encodeLong(writable, 6, span.getStartTime());
      // duration = 7, the time length of this span in nanoseconds
      encodeLong(writable, 7, PendingTrace.getDurationNano(span));
      // error = 8, if there is an error associated with this span
      encodeBoolean(writable, 8, span.getError() != 0);
      // attributes = 9, a collection of string key to value pairs on the span
      encodeSpanAttributes(writable, 9, meta, metaStruct);
      // type = 10, the string type of the service with which this span is associated
      // (example values: web, db, lambda)
      encodeString(writable, 10, span.getType());
      // links = 11, a collection of links to other spans
      encodeSpanLinks(writable, 11, meta.getSpanLinks());
      // events = 12, a collection of events that occurred during this span
      encodeSpanEvents(writable, 12, tags.getObject(DDTags.SPAN_EVENTS));
      // env = 13, the optional string environment of this span
      encodeString(writable, 13, tags.getString(Tags.ENV));
      // version = 14, the optional string version of this span
      encodeString(writable, 14, tags.getString(Tags.VERSION));
      // component = 15, the string component name of this span
      encodeString(writable, 15, tags.getString(Tags.COMPONENT));
      // kind = 16, the SpanKind of this span as defined in the OTEL Specification, uint32
      encodeInt(writable, 16, getSpanKindValue(tags.getString(Tags.SPAN_KIND)));

      meta = null; // Proceed to next span metadata.
    }
  }

  private void encodeSpanLinks(
      Writable writable, int fieldId, List<? extends AgentSpanLink> links) {
    writable.writeInt(fieldId);
    if (links == null || links.isEmpty()) {
      writable.startArray(0);
      return;
    }

    writable.startArray(links.size());
    for (AgentSpanLink link : links) {
      writable.startMap(5);
      // 1: the ID of the trace that this link targets
      encodeTraceId(writable, 1, link.traceId());
      // 2: the ID of the span that this link targets, fixed64
      encodeLongUnsigned(writable, 2, link.spanId());
      // 3: a collection of attribute string key to value pairs on the link, map<uint32, AnyValue>
      encodeAttributes(writable, 3, link.attributes().asMap());
      // 4: optional W3C trace state string
      encodeString(writable, 4, link.traceState());
      // 5: optional W3C trace flags (if set, the high bit, bit 31, must be set), uint32
      encodeInt(writable, 5, link.traceFlags() & 0xFF);
    }
  }

  private void encodeSpanEvents(Writable writable, int fieldId, Object eventsObject) {
    writable.writeInt(fieldId);
    if (!(eventsObject instanceof List) || ((List<?>) eventsObject).isEmpty()) {
      writable.startArray(0);
      return;
    }

    List<?> events = (List<?>) eventsObject;
    int encodableCount = 0;
    for (Object event : events) {
      if (isEncodableSpanEvent(event)) {
        encodableCount++;
      }
    }
    writable.startArray(encodableCount);
    for (Object event : events) {
      if (!(event instanceof Map)) {
        continue;
      }
      Map<?, ?> eventMap = (Map<?, ?>) event;
      Long timeUnixNano = asLong(eventMap.get("time_unix_nano"));
      Object nameObject = eventMap.get("name");
      if (timeUnixNano == null || nameObject == null) {
        continue;
      }

      Map<?, ?> attributes =
          eventMap.get("attributes") instanceof Map ? (Map<?, ?>) eventMap.get("attributes") : null;

      writable.startMap(3);
      encodeLong(writable, 1, timeUnixNano);
      encodeString(writable, 2, String.valueOf(nameObject));
      encodeEventAttributes(writable, 3, attributes);
    }
  }

  private boolean isEncodableSpanEvent(Object event) {
    if (!(event instanceof Map)) {
      return false;
    }
    Map<?, ?> eventMap = (Map<?, ?>) event;
    return eventMap.get("name") != null && asLong(eventMap.get("time_unix_nano")) != null;
  }

  private void encodeEventAttributes(Writable writable, int fieldId, Map<?, ?> attrs) {
    writable.writeInt(fieldId);
    if (attrs == null || attrs.isEmpty()) {
      writable.startArray(0);
      return;
    }

    int attributeCount = 0;
    for (Map.Entry<?, ?> entry : attrs.entrySet()) {
      if (isEncodableEventAttribute(entry.getValue())) {
        attributeCount++;
      }
    }
    writable.startArray(attributeCount * 3);

    for (Map.Entry<?, ?> entry : attrs.entrySet()) {
      Object value = entry.getValue();
      if (!isEncodableEventAttribute(value)) {
        continue;
      }
      writeStreamingString(writable, String.valueOf(entry.getKey()));
      writeEventAttributeValue(writable, value);
    }
  }

  private boolean isEncodableEventAttribute(Object value) {
    return value instanceof CharSequence
        || value instanceof Boolean
        || value instanceof Number
        || value instanceof List;
  }

  private void writeEventAttributeValue(Writable writable, Object value) {
    if (value instanceof Boolean) {
      writable.writeInt(VALUE_TYPE_BOOLEAN);
      writable.writeBoolean((Boolean) value);
      return;
    }
    if (value instanceof Number) {
      writeEventNumberValue(writable, (Number) value);
      return;
    }
    if (value instanceof List) {
      List<?> values = (List<?>) value;
      int itemCount = 0;
      for (Object item : values) {
        if (isEncodableEventArrayItem(item)) {
          itemCount++;
        }
      }
      writable.writeInt(VALUE_TYPE_ARRAY);
      writable.startArray(itemCount * 2);
      for (Object item : values) {
        if (!isEncodableEventArrayItem(item)) {
          continue;
        }
        writeEventArrayItemValue(writable, item);
      }
      return;
    }
    writable.writeInt(VALUE_TYPE_STRING);
    writeStreamingString(writable, String.valueOf(value));
  }

  private boolean isEncodableEventArrayItem(Object item) {
    return item instanceof CharSequence || item instanceof Boolean || item instanceof Number;
  }

  private void writeEventArrayItemValue(Writable writable, Object item) {
    if (item instanceof Boolean) {
      writable.writeInt(VALUE_TYPE_BOOLEAN);
      writable.writeBoolean((Boolean) item);
      return;
    }
    if (item instanceof Number) {
      writeEventNumberValue(writable, (Number) item);
      return;
    }
    writable.writeInt(VALUE_TYPE_STRING);
    writeStreamingString(writable, String.valueOf(item));
  }

  private void writeEventNumberValue(Writable writable, Number number) {
    if (isIntegralNumber(number)) {
      writable.writeInt(VALUE_TYPE_INT);
      writable.writeLong(number.longValue());
      return;
    }
    writable.writeInt(VALUE_TYPE_FLOAT);
    writable.writeDouble(number.doubleValue());
  }

  private boolean isIntegralNumber(Number number) {
    return !(number instanceof Float || number instanceof Double);
  }

  private Long asLong(Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof CharSequence) {
      try {
        return Long.parseLong(value.toString());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private void encodeSpanAttributes(
      Writable writable, int fieldId, Metadata meta, Map<String, Object> metaStruct) {
    TagMap tags = meta.getTags();
    Map<String, String> baggage = meta.getBaggage();
    String httpStatusCode =
        meta.getHttpStatusCode() == null ? null : meta.getHttpStatusCode().toString();
    boolean writeHttpStatus = httpStatusCode != null && tags.getString(HTTP_STATUS) == null;
    boolean writeTopLevel = meta.topLevel();
    int tagCount = 0;
    for (TagMap.EntryReader entry : tags) {
      if (!DDTags.SPAN_EVENTS.equals(entry.tag())) {
        tagCount += getFlatAttributeCount(entry);
      }
    }

    writable.writeInt(fieldId);
    writable.startArray(
        (tagCount
                + baggage.size()
                + metaStruct.size()
                + (writeHttpStatus ? 1 : 0)
                + (writeTopLevel ? 1 : 0))
            * 3);

    for (Map.Entry<String, String> entry : baggage.entrySet()) {
      writeAttribute(writable, entry.getKey(), entry.getValue());
    }

    for (TagMap.EntryReader entry : tags) {
      if (DDTags.SPAN_EVENTS.equals(entry.tag())) {
        continue;
      }
      writeFlattenedTagAttribute(writable, entry);
    }
    if (writeHttpStatus) {
      writeAttribute(writable, HTTP_STATUS, httpStatusCode);
    }
    if (writeTopLevel) {
      writeAttribute(writable, InstrumentationTags.DD_TOP_LEVEL.toString(), 1);
    }
    for (Map.Entry<String, Object> metaStructField : metaStruct.entrySet()) {
      writeStreamingString(writable, metaStructField.getKey());
      writable.writeInt(VALUE_TYPE_BYTES);
      writable.writeBinary(serializeMetaStructValue(metaStructField.getValue()));
    }
  }

  private int getFlatAttributeCount(TagMap.EntryReader entry) {
    return entry.isObject() ? getFlatAttributeCount(entry.objectValue()) : 1;
  }

  private int getFlatAttributeCount(Object value) {
    if (!(value instanceof Map)) {
      return 1;
    }

    int count = 0;
    for (Object nestedValue : ((Map<?, ?>) value).values()) {
      count += getFlatAttributeCount(nestedValue);
    }
    return count;
  }

  private void writeFlattenedTagAttribute(Writable writable, TagMap.EntryReader entry) {
    switch (entry.type()) {
      case TagMap.EntryReader.BOOLEAN:
        writeStreamingString(writable, entry.tag());
        writable.writeInt(VALUE_TYPE_BOOLEAN);
        writable.writeBoolean(entry.booleanValue());
        return;

      case TagMap.EntryReader.INT:
      case TagMap.EntryReader.LONG:
      case TagMap.EntryReader.FLOAT:
      case TagMap.EntryReader.DOUBLE:
        writeStreamingString(writable, entry.tag());
        writable.writeInt(VALUE_TYPE_FLOAT);
        writable.writeDouble(entry.doubleValue());
        return;

      default:
        writeFlattenedTagAttribute(writable, entry.tag(), entry.objectValue());
    }
  }

  private void writeFlattenedTagAttribute(Writable writable, String key, Object value) {
    if (!(value instanceof Map)) {
      writeAttribute(writable, key, value);
      return;
    }
    writeFlatMapAttributes(writable, key, (Map<?, ?>) value);
  }

  private void writeFlatMapAttributes(Writable writable, String keyPrefix, Map<?, ?> mapValue) {
    for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
      String key = keyPrefix + "." + entry.getKey();
      Object value = entry.getValue();
      if (value instanceof Map) {
        writeFlatMapAttributes(writable, key, (Map<?, ?>) value);
      } else {
        writeAttribute(writable, key, value);
      }
    }
  }

  private void encodeAttributes(Writable writable, int fieldId, Map<String, ?> attrs) {
    writable.writeInt(fieldId);
    writable.startArray(attrs.size() * 3); // Triplets: (key, type, value).

    for (Map.Entry<String, ?> attr : attrs.entrySet()) {
      writeAttribute(writable, attr.getKey(), attr.getValue());
    }
  }

  private void writeAttribute(Writable writable, String key, Object value) {
    writeStreamingString(writable, key);
    if (value instanceof Number) {
      writable.writeInt(VALUE_TYPE_FLOAT);
      writable.writeDouble(((Number) value).doubleValue());
      return;
    }
    if (value instanceof Boolean) {
      writable.writeInt(VALUE_TYPE_BOOLEAN);
      writable.writeBoolean((Boolean) value);
      return;
    }
    if (!(value instanceof String) && value != null) {
      log.debug("Not a string value for key: {}, value: {}", key, value);
    }
    writable.writeInt(VALUE_TYPE_STRING);
    writeStreamingString(writable, value == null ? "" : value.toString());
  }

  private byte[] serializeMetaStructValue(Object value) {
    metaStructBuffer.mark();
    try {
      metaStructWriter.writeObject(value, null);
      metaStructWriter.flush();
      ByteBuffer encoded = metaStructBuffer.slice();
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } finally {
      metaStructBuffer.reset();
    }
  }

  private void encodeBoolean(Writable writable, int fieldId, boolean value) {
    writable.writeInt(fieldId);
    writable.writeBoolean(value);
  }

  private void encodeInt(Writable writable, int fieldId, int value) {
    writable.writeInt(fieldId);
    writable.writeInt(value);
  }

  private void encodeLong(Writable writable, int fieldId, long value) {
    writable.writeInt(fieldId);
    writable.writeLong(value);
  }

  private void encodeLongUnsigned(Writable writable, int fieldId, long value) {
    writable.writeInt(fieldId);
    writable.writeUnsignedLong(value);
  }

  private void encodeString(Writable writable, int fieldId, CharSequence value) {
    writable.writeInt(fieldId);
    writeStreamingString(writable, value);
  }

  private void encodeTraceId(Writable writable, int fieldId, DDTraceId traceId) {
    writable.writeInt(fieldId);
    writable.writeBinary(traceId.toHighOrderLong(), traceId.toLong());
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
      return SPAN_KIND_UNSPECIFIED;
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
    encodeString(headerWriter, 2, ContainerInfo.get().getContainerId());

    // languageName = 3, the string language name of the tracer
    encodeString(headerWriter, 3, "java");

    // languageVersion = 4, the string language version of the tracer
    encodeString(headerWriter, 4, JavaVirtualMachine.getLangVersion());

    // tracerVersion = 5, the string version of the tracer
    encodeString(headerWriter, 5, TracerVersion.TRACER_VERSION);

    // runtimeID = 6, the V4 string UUID representation of a tracer session
    encodeString(headerWriter, 6, cfg.getRuntimeId());

    // env=7, the optional `env` string tag that set with the tracer
    encodeString(headerWriter, 7, cfg.getEnv());

    // hostname = 8, the optional string hostname of where the tracer is running
    encodeString(headerWriter, 8, cfg.getHostName());

    // appVersion = 9, the optional string `version` tag for the application set in the tracer
    encodeString(headerWriter, 9, cfg.getVersion());

    // attributes = 10, a collection of key to value pairs common in all `chunks`
    CharSequence processTags = ProcessTags.getTagsForSerialization();
    Map<String, Object> tags =
        processTags != null ? singletonMap(DDTags.PROCESS_TAGS, processTags) : emptyMap();
    encodeAttributes(headerWriter, 10, tags);

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
