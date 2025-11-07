package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.communication.serialization.Codec;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.Payload;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.core.PendingTrace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;

public final class TraceMapperV0_4 implements TraceMapper {
  static final SimpleUtf8Cache TAG_CACHE =
      Config.get().getTagNameUtf8CacheSize() > 0
          ? new SimpleUtf8Cache(Config.get().getTagNameUtf8CacheSize())
          : null;

  static final GenerationalUtf8Cache VALUE_CACHE =
      Config.get().getTagValueUtf8CacheSize() > 0
          ? new GenerationalUtf8Cache(Config.get().getTagValueUtf8CacheSize())
          : null;

  private final int size;
  private boolean firstSpanWritten;

  public TraceMapperV0_4(int size) {
    this.size = size;
  }

  public TraceMapperV0_4() {
    this(5 << 20);
  }

  private static final class MetaWriter implements MetadataConsumer {

    private Writable writable;
    private boolean firstSpanInTrace;
    private boolean lastSpanInTrace;
    private boolean firstSpanInPayload;

    MetaWriter withWritable(Writable writable) {
      this.writable = writable;
      return this;
    }

    MetaWriter forSpan(boolean firstInTrace, boolean lastInTrace, boolean firstInPayload) {
      this.firstSpanInTrace = firstInTrace;
      this.lastSpanInTrace = lastInTrace;
      this.firstSpanInPayload = firstInPayload;
      return this;
    }

    @Override
    public void accept(Metadata metadata) {
      if (TAG_CACHE != null) TAG_CACHE.recalibrate();
      if (VALUE_CACHE != null) VALUE_CACHE.recalibrate();

      final boolean writeSamplingPriority = firstSpanInTrace || lastSpanInTrace;
      final UTF8BytesString processTags = firstSpanInPayload ? metadata.processTags() : null;
      int metaSize =
          metadata.getBaggage().size()
              + metadata.getTags().size()
              + (null == metadata.getHttpStatusCode() ? 0 : 1)
              + (null == metadata.getOrigin() ? 0 : 1)
              + (null == processTags ? 0 : 1)
              + 1;
      int metricsSize =
          (writeSamplingPriority && metadata.hasSamplingPriority() ? 1 : 0)
              + (metadata.measured() ? 1 : 0)
              + (metadata.topLevel() ? 1 : 0)
              + (metadata.longRunningVersion() != 0 ? 1 : 0)
              + 1;
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        Object value = tag.getValue();
        if (value instanceof Number) {
          ++metricsSize;
          --metaSize;
        } else if (value instanceof Map) {
          // Compute size based on amount of elements in tree
          --metaSize;
          metaSize += getFlatMapSize((Map) value);
        }
      }
      writable.writeUTF8(METRICS);
      writable.startMap(metricsSize);
      if (writeSamplingPriority && metadata.hasSamplingPriority()) {
        writable.writeUTF8(SAMPLING_PRIORITY_KEY);
        writable.writeInt(metadata.samplingPriority());
      }
      if (metadata.measured()) {
        writable.writeUTF8(InstrumentationTags.DD_MEASURED);
        writable.writeInt(1);
      }
      if (metadata.topLevel()) {
        writable.writeUTF8(InstrumentationTags.DD_TOP_LEVEL);
        writable.writeInt(1);
      }
      if (metadata.longRunningVersion() != 0) {
        if (metadata.longRunningVersion() > 0) {
          writable.writeUTF8(InstrumentationTags.DD_PARTIAL_VERSION);
          writable.writeInt(metadata.longRunningVersion());
        } else {
          writable.writeUTF8(InstrumentationTags.DD_WAS_LONG_RUNNING);
          writable.writeInt(1);
        }
      }
      writable.writeUTF8(THREAD_ID);
      writable.writeLong(metadata.getThreadId());
      for (Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        if (entry.getValue() instanceof Number) {
          writable.writeString(entry.getKey(), TAG_CACHE);
          writable.writeObject(entry.getValue(), VALUE_CACHE);
        }
      }

      writable.writeUTF8(META);
      writable.startMap(metaSize);
      // we don't need to deduplicate any overlap between tags and baggage here
      // since they will be accumulated into maps in the same order downstream,
      // we just need to be sure that the size is the same as the number of elements
      for (Map.Entry<String, String> entry : metadata.getBaggage().entrySet()) {
        writable.writeString(entry.getKey(), TAG_CACHE);
        writable.writeString(entry.getValue(), VALUE_CACHE);
      }
      writable.writeUTF8(THREAD_NAME);
      writable.writeUTF8(metadata.getThreadName());
      if (null != metadata.getHttpStatusCode()) {
        writable.writeUTF8(HTTP_STATUS);
        writable.writeUTF8(metadata.getHttpStatusCode());
      }
      if (null != metadata.getOrigin()) {
        writable.writeUTF8(ORIGIN_KEY);
        writable.writeString(metadata.getOrigin(), VALUE_CACHE);
      }
      if (processTags != null) {
        writable.writeUTF8(PROCESS_TAGS_KEY);
        writable.writeUTF8(processTags);
      }
      for (Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        if (value instanceof Map) {
          // Write map as flat map
          writeFlatMap(key, (Map) value);
        } else if (!(value instanceof Number)) {
          writable.writeString(entry.getKey(), TAG_CACHE);
          writable.writeObjectString(entry.getValue(), VALUE_CACHE);
        }
      }
    }

    /**
     * Calculate number of all values from map and all sub-maps Assuming map could be a binary tree
     *
     * @param map map to traverse
     * @return number of all elements in the tree
     */
    private int getFlatMapSize(Map<String, Object> map) {
      int size = 0;
      for (Object value : map.values()) {
        if (value instanceof Map) {
          size += getFlatMapSize((Map) value);
        } else {
          size++;
        }
      }
      return size;
    }

    /**
     * Method write map of maps into writeable as FlatMap
     *
     * <p>Example: "root": { "key1": "val1" "key2": { "sub1": "val2", "sub2": "val3" } } "plain":
     * "123"
     *
     * <p>Result: "root.key1" -> "val1" "root.key2.sub1" -> "val2" "root.key2.sub2" -> "val3"
     * "plain" -> "123"
     *
     * @param key key name used as base
     * @param mapValue map of tags that can contain sub-maps as values
     */
    private void writeFlatMap(String key, Map<String, Object> mapValue) {
      for (Map.Entry<String, Object> entry : mapValue.entrySet()) {
        String newKey = key + '.' + entry.getKey();
        Object newValue = entry.getValue();
        if (newValue instanceof Map) {
          writeFlatMap(newKey, (Map) newValue);
        } else {
          writable.writeString(newKey, TAG_CACHE);
          writable.writeObjectString(newValue, VALUE_CACHE);
        }
      }
    }
  }

  /**
   * The MetaStruct field can safely be used with v4 agents and will be discarded for other
   * versions.
   *
   * <p>Any type that needs to be serialized as part of the meta_struct field has to either be a JDK
   * known type (primitives, wrappers, collections ...) or registered with {@link
   * datadog.communication.serialization.Codec#Codec(Map)}, in the rest of the cases the {@code
   * toString} representation of the object will be used instead
   */
  public static class MetaStructWriter {

    private static final UTF8BytesString META_STRUCT = UTF8BytesString.create("meta_struct");
    private static final int BUFFER_SIZE = 1 << 10;

    private Writable writable;

    MetaStructWriter withWritable(final Writable writable) {
      this.writable = writable;
      return this;
    }

    public void write(final Map<String, Object> metaStruct) {
      writable.writeUTF8(META_STRUCT);
      writable.startMap(metaStruct.size());
      final GrowableBuffer buffer = new GrowableBuffer(BUFFER_SIZE);
      final MsgPackWriter metaStructWriter = new MsgPackWriter(Codec.INSTANCE, buffer);
      for (Map.Entry<String, Object> entry : metaStruct.entrySet()) {
        writeMetaStructEntry(metaStructWriter, buffer, entry.getKey(), entry.getValue());
      }
    }

    private void writeMetaStructEntry(
        final MsgPackWriter writer,
        final GrowableBuffer buffer,
        final String key,
        final Object value) {
      buffer.mark();
      try {
        writer.writeObject(value, null);
        writer.flush();
        writable.writeString(key, TAG_CACHE);
        writable.writeBinary(buffer.slice());
      } finally {
        buffer.reset();
      }
    }
  }

  private final MetaWriter metaWriter = new MetaWriter();
  private final MetaStructWriter metaStructWriter = new MetaStructWriter();

  @Override
  public void map(List<? extends CoreSpan<?>> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (int i = 0; i < trace.size(); i++) {
      final CoreSpan<?> span = trace.get(i);
      final Map<String, Object> metaStruct = span.getMetaStruct();
      writable.startMap(metaStruct.isEmpty() ? 12 : 13);
      /* 1  */
      writable.writeUTF8(SERVICE);
      writable.writeString(span.getServiceName(), VALUE_CACHE);
      /* 2  */
      writable.writeUTF8(NAME);
      writable.writeObject(span.getOperationName(), VALUE_CACHE);
      /* 3  */
      writable.writeUTF8(RESOURCE);
      writable.writeObject(span.getResourceName(), VALUE_CACHE);
      /* 4  */
      writable.writeUTF8(TRACE_ID);
      writable.writeUnsignedLong(span.getTraceId().toLong());
      /* 5  */
      writable.writeUTF8(SPAN_ID);
      writable.writeUnsignedLong(span.getSpanId());
      /* 6  */
      writable.writeUTF8(PARENT_ID);
      writable.writeUnsignedLong(span.getParentId());
      /* 7  */
      writable.writeUTF8(START);
      writable.writeLong(span.getStartTime());
      /* 8  */
      writable.writeUTF8(DURATION);
      writable.writeLong(PendingTrace.getDurationNano(span));
      /* 9  */
      writable.writeUTF8(TYPE);
      writable.writeString(span.getType(), VALUE_CACHE);
      /* 10 */
      writable.writeUTF8(ERROR);
      writable.writeInt(span.getError());
      /* 11, 12 */
      span.processTagsAndBaggage(
          metaWriter
              .withWritable(writable)
              .forSpan(i == 0, i == trace.size() - 1, !firstSpanWritten));
      if (!metaStruct.isEmpty()) {
        /* 13 */
        metaStructWriter.withWritable(writable).write(metaStruct);
      }
      firstSpanWritten = true;
    }
  }

  @Override
  public Payload newPayload() {
    return new PayloadV0_4();
  }

  @Override
  public int messageBufferSize() {
    return size; // 5MB
  }

  @Override
  public void reset() {
    firstSpanWritten = false;
  }

  @Override
  public String endpoint() {
    return "v0.4";
  }

  private static class PayloadV0_4 extends Payload {

    @Override
    public int sizeInBytes() {
      return msgpackArrayHeaderSize(traceCount()) + body.remaining();
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      ByteBuffer header = msgpackArrayHeader(traceCount());
      while (header.hasRemaining()) {
        channel.write(header);
      }
      while (body.hasRemaining()) {
        channel.write(body);
      }
    }

    @Override
    public RequestBody toRequest() {
      return msgpackRequestBodyOf(Arrays.asList(msgpackArrayHeader(traceCount()), body));
    }
  }
}
