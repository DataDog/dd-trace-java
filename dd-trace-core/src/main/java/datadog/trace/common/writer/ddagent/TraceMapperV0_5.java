package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Mapper;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.TagMap;
import datadog.trace.api.TagMap.EntryReader;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;

public final class TraceMapperV0_5 implements TraceMapper {

  private final WritableFormatter dictionaryWriter;
  private final DictionaryMapper dictionaryMapper = new DictionaryMapper();
  private final Map<Object, Integer> encoding = new HashMap<>();
  private final GrowableBuffer dictionary;

  private final MetaWriter metaWriter = new MetaWriter();
  private final int size;
  private boolean firstSpanWritten;

  public TraceMapperV0_5() {
    this(2 << 20);
  }

  public TraceMapperV0_5(int dictionarySize, int bufferSize) {
    // growable buffer is implicitly bounded by the fixed size buffer
    // the messages themselves are written into
    this.dictionary = new GrowableBuffer(bufferSize);
    this.dictionaryWriter = new MsgPackWriter(dictionary);
    this.size = bufferSize;
    reset();
  }

  public TraceMapperV0_5(final int dictionarySize) {
    this(dictionarySize, 2 << 20);
  }

  @Override
  public void map(final List<? extends CoreSpan<?>> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (int i = 0; i < trace.size(); i++) {
      final CoreSpan<?> span = trace.get(i);
      writable.startArray(12);
      /* 1  */
      writeDictionaryEncoded(writable, span.getServiceName());
      /* 2  */
      writeDictionaryEncoded(writable, span.getOperationName());
      /* 3  */
      writeDictionaryEncoded(writable, span.getResourceName());
      /* 4  */
      writable.writeUnsignedLong(span.getTraceId().toLong());
      /* 5  */
      writable.writeUnsignedLong(span.getSpanId());
      /* 6  */
      writable.writeUnsignedLong(span.getParentId());
      /* 7  */
      writable.writeLong(span.getStartTime());
      /* 8  */
      writable.writeLong(PendingTrace.getDurationNano(span));
      /* 9  */
      writable.writeInt(span.getError());
      /* 10, 11  */
      span.processTagsAndBaggage(
          metaWriter
              .withWritable(writable)
              .forSpan(i == 0, i == trace.size() - 1, !firstSpanWritten));
      /* 12 */
      writeDictionaryEncoded(writable, span.getType());
      firstSpanWritten = true;
    }
  }

  private void writeDictionaryEncoded(final Writable writable, final Object value) {
    final Object target = null == value ? "" : value;
    final Integer encoded = encoding.get(target);
    if (null == encoded) {
      dictionaryWriter.format(target, dictionaryMapper);
      final int dictionaryCode = dictionary.messageCount() - 1;
      encoding.put(target, dictionaryCode);
      // this call can fail, but the dictionary has been written to now
      // so should make sure dictionary state is consistent first
      writable.writeInt(dictionaryCode);
    } else {
      writable.writeInt(encoded);
    }
  }

  @Override
  public Payload newPayload() {
    return new PayloadV0_5(dictionary.slice(), dictionary.messageCount());
  }

  @Override
  public int messageBufferSize() {
    return size; // 2MB
  }

  @Override
  public void reset() {
    dictionary.reset();
    encoding.clear();
    firstSpanWritten = false;
  }

  @Override
  public String endpoint() {
    return "v0.5";
  }

  private static class DictionaryMapper implements Mapper<Object> {

    @Override
    public void map(final Object data, final Writable packer) {
      if (data instanceof UTF8BytesString) {
        packer.writeObject(data, null);
      } else {
        packer.writeString(String.valueOf(data), null);
      }
    }
  }

  private static class PayloadV0_5 extends Payload {

    private final ByteBuffer dictionary;
    private final int stringCount;

    private PayloadV0_5(ByteBuffer dictionary, int stringCount) {
      this.dictionary = dictionary;
      this.stringCount = stringCount;
    }

    @Override
    public int sizeInBytes() {
      return 1
          + msgpackArrayHeaderSize(stringCount)
          + dictionary.remaining()
          + msgpackArrayHeaderSize(traceCount())
          + body.remaining();
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      for (ByteBuffer buffer : toList()) {
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
      }
    }

    @Override
    public RequestBody toRequest() {
      return msgpackRequestBodyOf(toList());
    }

    private List<ByteBuffer> toList() {
      return Arrays.asList(
          // msgpack array header with 2 elements (FIXARRAY | 2)
          ByteBuffer.allocate(1).put(0, (byte) 0x92),
          msgpackArrayHeader(stringCount),
          dictionary,
          msgpackArrayHeader(traceCount()),
          body);
    }
  }

  private final class MetaWriter implements MetadataConsumer {

    private Writable writable;
    private boolean firstSpanInTrace;
    private boolean lastSpanInTrace;
    private boolean firstSpanInPayload;

    MetaWriter withWritable(final Writable writable) {
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
      final boolean writeSamplingPriority = firstSpanInTrace || lastSpanInTrace;
      final UTF8BytesString processTags = firstSpanInPayload ? metadata.processTags() : null;
      
      TagMap tags = metadata.getTags();
      
      int metaSize =
          metadata.getBaggage().size()
              + tags.size()
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
      
      for (TagMap.EntryReader entry : tags) {
        if (entry.isNumber()) {
          ++metricsSize;
          --metaSize;
        } else {
          Object value = entry.objectValue();
          if (value instanceof Map) {
            --metaSize;
            metaSize += getFlatMapSize((Map) value);
          }
        }
      }
      
      writable.startMap(metaSize);
      // we don't need to deduplicate any overlap between tags and baggage here
      // since they will be accumulated into maps in the same order downstream,
      // we just need to be sure that the size is the same as the number of elements
      for (Map.Entry<String, String> entry : metadata.getBaggage().entrySet()) {
        writeDictionaryEncoded(writable, entry.getKey());
        writeDictionaryEncoded(writable, entry.getValue());
      }
      writeDictionaryEncoded(writable, THREAD_NAME);
      writeDictionaryEncoded(writable, metadata.getThreadName());
      if (null != metadata.getHttpStatusCode()) {
        writeDictionaryEncoded(writable, HTTP_STATUS);
        writeDictionaryEncoded(writable, metadata.getHttpStatusCode());
      }
      if (null != metadata.getOrigin()) {
        writeDictionaryEncoded(writable, ORIGIN_KEY);
        writeDictionaryEncoded(writable, metadata.getOrigin());
      }
      if (null != processTags) {
        writeDictionaryEncoded(writable, PROCESS_TAGS_KEY);
        writeDictionaryEncoded(writable, processTags);
      }
      
      for (TagMap.EntryReader entry : tags) {
        if (entry.isNumber()) continue;
        
        String key = entry.tag();
        Object value = entry.objectValue();

        if (value instanceof Map) {
          // Write map as flat map
          writeFlatMap(key, (Map) value);
        } else {
          writeDictionaryEncoded(writable, key);
          writeDictionaryEncoded(writable, value);
        }
      }
      writable.startMap(metricsSize);
      if (writeSamplingPriority && metadata.hasSamplingPriority()) {
        writeDictionaryEncoded(writable, SAMPLING_PRIORITY_KEY);
        writable.writeInt(metadata.samplingPriority());
      }
      if (metadata.measured()) {
        writeDictionaryEncoded(writable, InstrumentationTags.DD_MEASURED);
        writable.writeInt(1);
      }
      if (metadata.topLevel()) {
        writeDictionaryEncoded(writable, InstrumentationTags.DD_TOP_LEVEL);
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
      writeDictionaryEncoded(writable, THREAD_ID);
      writable.writeLong(metadata.getThreadId());
      
      for ( EntryReader entry: metadata.getTags() ) {
    	if (!entry.isNumber()) continue;
      
    	writeDictionaryEncoded(writable, entry.tag());
        switch (entry.type()) {
          case TagMap.EntryReader.INT:
            writable.writeInt(entry.intValue());
            break;

          case TagMap.EntryReader.LONG:
            writable.writeLong(entry.longValue());
            break;

          case TagMap.EntryReader.FLOAT:
            writable.writeFloat(entry.floatValue());
            break;

          case TagMap.EntryReader.DOUBLE:
            writable.writeDouble(entry.doubleValue());
            break;

          default:
            writable.writeObject(entry.objectValue(), null);
            break;
        }
      }
    }

    /**
     * Calculate number of all values from map and all sub-maps Assuming map could be a binary tree
     *
     * @param map map to traverse
     * @return number of all elements in the tree
     */
    int getFlatMapSize(Map<String, Object> map) {
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
          writeDictionaryEncoded(writable, newKey);
          writeDictionaryEncoded(writable, newValue);
        }
      }
    }
  }
}
