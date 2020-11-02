package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.serialization.EncodingCachingStrategies.NO_CACHING;
import static datadog.trace.core.serialization.Util.integerToStringBuffer;
import static datadog.trace.core.serialization.Util.writeLongAsString;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanData;
import datadog.trace.core.StringTables;
import datadog.trace.core.TagsAndBaggageConsumer;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.Writable;
import datadog.trace.core.serialization.WritableFormatter;
import datadog.trace.core.serialization.msgpack.MsgPackWriter;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TraceMapperV0_5 implements TraceMapper {

  static final byte[] EMPTY =
      ByteBuffer.allocate(3).put((byte) 0x92).put((byte) 0x90).put((byte) 0x90).array();

  private static final DictionaryFull DICTIONARY_FULL = new DictionaryFull();

  private final ByteBuffer[] dictionary = new ByteBuffer[1];
  private final WritableFormatter dictionaryWriter;
  private final DictionaryMapper dictionaryMapper = new DictionaryMapper();
  private final Map<Object, Integer> encoding = new HashMap<>();

  private final MetaWriter metaWriter = new MetaWriter();

  public TraceMapperV0_5() {
    this(2 << 20);
  }

  public TraceMapperV0_5(final int bufferSize) {
    this.dictionaryWriter =
        new MsgPackWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(final int messageCount, final ByteBuffer buffer) {
                dictionary[0] = buffer;
              }
            },
            ByteBuffer.allocate(bufferSize),
            true);
    reset();
  }

  @Override
  public void map(final List<? extends DDSpanData> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (final DDSpanData span : trace) {
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
      span.processTagsAndBaggage(metaWriter.withWritable(writable));
      /* 11  */
      writable.startMap(span.getMetrics().size());
      for (final Map.Entry<CharSequence, Number> entry : span.getMetrics().entrySet()) {
        writeDictionaryEncoded(writable, entry.getKey());
        writable.writeObject(entry.getValue(), NO_CACHING);
      }
      /* 12 */
      writeDictionaryEncoded(writable, span.getType());
    }
  }

  private void writeDictionaryEncoded(final Writable writable, final Object value) {
    final Object target = null == value ? "" : value;
    final Integer encoded = encoding.get(target);
    if (null == encoded) {
      if (!dictionaryWriter.format(target, dictionaryMapper)) {
        dictionaryWriter.flush();
        // signal the need for a flush because the string table filled up
        // faster than the message content
        throw DICTIONARY_FULL;
      }
      final int dictionaryCode = dictionaryWriter.messageCount() - 1;
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
    return new PayloadV0_5(getDictionary());
  }

  @Override
  public int messageBufferSize() {
    return 2 << 20; // 2MB
  }

  private ByteBuffer getDictionary() {
    if (dictionary[0] == null) {
      // this has the side effect of populating the dictionary array
      // (this is a prime candidate for refactoring)
      dictionaryWriter.flush();
    }
    return dictionary[0];
  }

  @Override
  public void reset() {
    dictionaryWriter.reset();
    dictionary[0] = null;
    encoding.clear();
  }

  @Override
  public String endpoint() {
    return "v0.5";
  }

  private static class DictionaryMapper implements Mapper<Object> {

    private final byte[] numberByteArray = integerToStringBuffer();

    @Override
    public void map(final Object data, final Writable packer) {
      if (data instanceof UTF8BytesString) {
        packer.writeObject(data, NO_CACHING);
      } else if (data instanceof Long || data instanceof Integer) {
        writeLongAsString(((Number) data).longValue(), packer, numberByteArray);
      } else {
        assert null != data : "enclosing mapper should not provide null values";
        final String string = String.valueOf(data);
        final byte[] utf8 = StringTables.getKeyBytesUTF8(string);
        if (null == utf8) {
          packer.writeString(string, NO_CACHING);
          return;
        }
        packer.writeUTF8(utf8);
      }
    }
  }

  private static class PayloadV0_5 extends Payload {

    // msgpack array header with 2 elements (FIXARRAY | 2)
    private final ByteBuffer header = ByteBuffer.allocate(1).put(0, (byte) 0x92);
    private final ByteBuffer dictionary;

    private PayloadV0_5(final ByteBuffer dictionary) {
      this.dictionary = dictionary;
    }

    @Override
    int sizeInBytes() {
      return sizeInBytes(header) + sizeInBytes(dictionary) + sizeInBytes(body);
    }

    @Override
    public void writeTo(final WritableByteChannel channel) throws IOException {
      writeBufferToChannel(header, channel);
      writeBufferToChannel(dictionary, channel);
      writeBufferToChannel(body, channel);
    }
  }

  private static final class DictionaryFull extends BufferOverflowException {
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  private final class MetaWriter extends TagsAndBaggageConsumer {

    private Writable writable;

    MetaWriter withWritable(final Writable writable) {
      this.writable = writable;
      return this;
    }

    @Override
    public void accept(final Map<String, Object> tags, final Map<String, String> baggage) {
      // since tags can "override" baggage, we need to count the non overlapping ones
      int size = tags.size();
      // assume we can't have more than 64 baggage items,
      // and that iteration order is stable to avoid looking
      // up in the tags more than necessary
      long overlaps = 0L;
      if (!baggage.isEmpty()) {
        int i = 0;
        for (final Map.Entry<String, String> key : baggage.entrySet()) {
          if (!tags.containsKey(key.getKey())) {
            size++;
          } else {
            overlaps |= (1L << i);
          }
          ++i;
        }
      }
      writable.startMap(size);
      int i = 0;
      for (final Map.Entry<String, String> entry : baggage.entrySet()) {
        // tags and baggage may intersect, but tags take priority
        if ((overlaps & (1L << i)) == 0) {
          writeDictionaryEncoded(writable, entry.getKey());
          writeDictionaryEncoded(writable, entry.getValue());
        }
        ++i;
      }
      for (final Map.Entry<String, Object> entry : tags.entrySet()) {
        writeDictionaryEncoded(writable, entry.getKey());
        writeDictionaryEncoded(writable, entry.getValue());
      }
    }
  }
}
