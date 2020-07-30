package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.CONSTANT_KEYS;
import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.NO_CACHING;
import static datadog.trace.core.serialization.msgpack.Util.writeLongAsString;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanData;
import datadog.trace.core.StringTables;
import datadog.trace.core.TagsAndBaggageConsumer;
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer;
import datadog.trace.core.serialization.msgpack.Mapper;
import datadog.trace.core.serialization.msgpack.Packer;
import datadog.trace.core.serialization.msgpack.Writable;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TraceMapperV0_5 implements TraceMapper {

  private static final class DictionaryFull extends BufferOverflowException {
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  private static final DictionaryFull DICTIONARY_FULL = new DictionaryFull();

  private final ByteBuffer[] dictionary = new ByteBuffer[1];
  private final Packer dictionaryWriter;
  private final DictionaryMapper dictionaryMapper = new DictionaryMapper();
  private final Map<Object, Integer> encoding = new HashMap<>();
  private int code = 0;

  public TraceMapperV0_5() {
    this(2 << 20);
  }

  public TraceMapperV0_5(int bufferSize) {
    this.dictionaryWriter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffer) {
                dictionary[0] = buffer;
              }
            },
            ByteBuffer.allocate(bufferSize),
            true);
    reset();
  }

  @Override
  public void map(List<? extends DDSpanData> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (DDSpanData span : trace) {
      writable.startArray(12);
      /* 1  */
      writeDictionaryEncoded(writable, span.getServiceName());
      /* 2  */
      writeDictionaryEncoded(writable, span.getOperationName());
      /* 3  */
      writeDictionaryEncoded(writable, span.getResourceName());
      /* 4  */
      writable.writeLong(span.getTraceId().toLong());
      /* 5  */
      writable.writeLong(span.getSpanId().toLong());
      /* 6  */
      writable.writeLong(span.getParentId().toLong());
      /* 7  */
      writable.writeLong(span.getStartTime());
      /* 8  */
      writable.writeLong(span.getDurationNano());
      /* 9  */
      writable.writeInt(span.getError());
      /* 10  */
      span.processTagsAndBaggage(
        new TagsAndBaggageConsumer() {
          @Override
          public void accept(Map<String, Object> tags, Map<String, String> baggage) {
            // since tags can "override" baggage, we need to count the non overlapping ones
            int size = tags.size();
            boolean overlap = false;
            for (String key : baggage.keySet()) {
              if (!tags.containsKey(key)) {
                size++;
              } else {
                overlap = true;
              }
            }
            writable.startMap(size);
            for (Map.Entry<String, String> entry : baggage.entrySet()) {
              // tags and baggage may intersect, but tags take priority
              if (!overlap || !tags.containsKey(entry.getKey())) {
                writeDictionaryEncoded(writable, entry.getKey());
                writeDictionaryEncoded(writable, entry.getValue());
              }
            }
            for (Map.Entry<String, Object> entry : tags.entrySet()) {
              writeDictionaryEncoded(writable, entry.getKey());
              writeDictionaryEncoded(writable, entry.getValue());
            }
          }
        });
      /* 11  */
      writable.startMap(span.getMetrics().size());
      for (Map.Entry<String, Number> entry : span.getMetrics().entrySet()) {
        writeDictionaryEncoded(writable, entry.getKey());
        writable.writeObject(entry.getValue(), NO_CACHING);
      }
      /* 12 */
      writeDictionaryEncoded(writable, span.getType());
    }
  }

  private void writeDictionaryEncoded(Writable writable, Object value) {
    Object target = null == value ? "" : value;
    Integer encoded = encoding.get(target);
    if (null == encoded) {
      if (!dictionaryWriter.format(target, dictionaryMapper)) {
        dictionaryWriter.flush();
        // signal the need for a flush because the string table filled up
        // faster than the message content
        throw DICTIONARY_FULL;
      }
      encoding.put(target, code);
      writable.writeInt(code);
      ++code;
    } else {
      writable.writeInt(encoded);
    }
  }

  @Override
  public Payload newPayload() {
    return new PayloadV0_5(getDictionary());
  }

  private ByteBuffer getDictionary() {
    if (dictionary[0] == null) {
      dictionaryWriter.flush();
    }
    return dictionary[0];
  }

  @Override
  public void reset() {
    dictionaryWriter.reset();
    code = 0;
    dictionary[0] = null;
    encoding.clear();
  }

  private static class DictionaryMapper implements Mapper<Object> {

    private final byte[] numberByteArray = new byte[20]; // this is max long digits and sign

    @Override
    public void map(Object data, Writable packer) {
      if (data instanceof UTF8BytesString) {
        packer.writeUTF8(((UTF8BytesString) data).getUtf8Bytes());
      } else if (data instanceof Long || data instanceof Integer) {
        writeLongAsString(((Number) data).longValue(), packer, numberByteArray);
      } else {
        assert null != data : "enclosing mapper should not provide null values";
        String string = String.valueOf(data);
        byte[] utf8 = StringTables.getKeyBytesUTF8(string);
        if (null == utf8) {
          utf8 = StringTables.getTagBytesUTF8(string);
          if (null == utf8) {
            packer.writeString(string, NO_CACHING);
            return;
          }
        }
        packer.writeUTF8(utf8);
      }
    }
  }

  private static class PayloadV0_5 extends Payload {

    // msgpack array header with 2 elements (FIXARRAY | 2)
    private final ByteBuffer header = ByteBuffer.allocate(1).put(0, (byte) 0x92);
    private final ByteBuffer dictionary;

    private PayloadV0_5(ByteBuffer dictionary) {
      this.dictionary = dictionary;
    }

    @Override
    int sizeInBytes() {
      return sizeInBytes(header) + sizeInBytes(dictionary) + sizeInBytes(body);
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      writeBufferToChannel(header, channel);
      writeBufferToChannel(dictionary, channel);
      writeBufferToChannel(body, channel);
    }
  }
}
