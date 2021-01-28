package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.http.OkHttpUtils.msgpackRequestBodyOf;
import static datadog.trace.core.serialization.EncodingCachingStrategies.NO_CACHING;
import static datadog.trace.core.serialization.Util.integerToStringBuffer;
import static datadog.trace.core.serialization.Util.writeLongAsString;

import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.core.StringTables;
import datadog.trace.core.serialization.GrowableBuffer;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.Writable;
import datadog.trace.core.serialization.WritableFormatter;
import datadog.trace.core.serialization.msgpack.MsgPackWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;

public final class TraceMapperV0_5 implements TraceMapper {

  static final byte[] EMPTY =
      ByteBuffer.allocate(3).put((byte) 0x92).put((byte) 0x90).put((byte) 0x90).array();

  private final WritableFormatter dictionaryWriter;
  private final DictionaryMapper dictionaryMapper = new DictionaryMapper();
  private final Map<Object, Integer> encoding = new HashMap<>();
  private final GrowableBuffer dictionary;

  private final MetaWriter metaWriter = new MetaWriter();

  public TraceMapperV0_5() {
    this(2 << 20);
  }

  public TraceMapperV0_5(final int bufferSize) {
    // growable buffer is implicitly bounded by the fixed size buffer
    // the messages themselves are written into
    this.dictionary = new GrowableBuffer(bufferSize);
    this.dictionaryWriter = new MsgPackWriter(dictionary);
    reset();
  }

  @Override
  public void map(final List<? extends CoreSpan<?>> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (final CoreSpan<?> span : trace) {
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
      writeMetrics(span, writable);
      /* 12 */
      writeDictionaryEncoded(writable, span.getType());
    }
  }

  private void writeMetrics(CoreSpan<?> span, Writable writable) {
    Map<CharSequence, Number> metrics = span.getUnsafeMetrics();
    int elementCount = metrics.size();
    elementCount += (span.hasSamplingPriority() ? 1 : 0);
    elementCount += (span.isMeasured() ? 1 : 0);
    elementCount += (span.isTopLevel() ? 1 : 0);
    writable.startMap(elementCount);
    if (span.hasSamplingPriority()) {
      writeDictionaryEncoded(writable, SAMPLING_PRIORITY_KEY);
      writable.writeInt(span.samplingPriority());
    }
    if (span.isMeasured()) {
      writeDictionaryEncoded(writable, InstrumentationTags.DD_MEASURED);
      writable.writeInt(1);
    }
    if (span.isTopLevel()) {
      writeDictionaryEncoded(writable, InstrumentationTags.DD_TOP_LEVEL);
      writable.writeInt(1);
    }
    for (Map.Entry<CharSequence, Number> metric : metrics.entrySet()) {
      writeDictionaryEncoded(writable, metric.getKey());
      writable.writeObject(metric.getValue(), NO_CACHING);
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
    return 2 << 20; // 2MB
  }

  @Override
  public void reset() {
    dictionary.reset();
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

    private final ByteBuffer dictionary;
    private final int stringCount;

    private PayloadV0_5(ByteBuffer dictionary, int stringCount) {
      this.dictionary = dictionary;
      this.stringCount = stringCount;
    }

    @Override
    int sizeInBytes() {
      return 1
          + msgpackArrayHeaderSize(stringCount)
          + dictionary.remaining()
          + msgpackArrayHeaderSize(traceCount())
          + body.remaining();
    }

    @Override
    void writeTo(WritableByteChannel channel) throws IOException {
      for (ByteBuffer buffer : toList()) {
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
      }
    }

    @Override
    RequestBody toRequest() {
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

  private final class MetaWriter extends MetadataConsumer {

    private Writable writable;

    MetaWriter withWritable(final Writable writable) {
      this.writable = writable;
      return this;
    }

    @Override
    public void accept(Metadata metadata) {
      // since tags can "override" baggage, we need to count the non overlapping ones
      int size = metadata.getTags().size() + 2;
      // assume we can't have more than 64 baggage items,
      // and that iteration order is stable to avoid looking
      // up in the tags more than necessary
      long overlaps = 0L;
      if (!metadata.getBaggage().isEmpty()) {
        int i = 0;
        for (final Map.Entry<String, String> key : metadata.getBaggage().entrySet()) {
          if (!metadata.getTags().containsKey(key.getKey())) {
            size++;
          } else {
            overlaps |= (1L << i);
          }
          ++i;
        }
      }
      writable.startMap(size);
      int i = 0;
      for (final Map.Entry<String, String> entry : metadata.getBaggage().entrySet()) {
        // tags and baggage may intersect, but tags take priority
        if ((overlaps & (1L << i)) == 0) {
          writeDictionaryEncoded(writable, entry.getKey());
          writeDictionaryEncoded(writable, entry.getValue());
        }
        ++i;
      }
      writeDictionaryEncoded(writable, THREAD_NAME);
      writeDictionaryEncoded(writable, metadata.getThreadName());
      writeDictionaryEncoded(writable, THREAD_ID);
      writeDictionaryEncoded(writable, String.valueOf(metadata.getThreadId()));
      for (final Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        writeDictionaryEncoded(writable, entry.getKey());
        writeDictionaryEncoded(writable, entry.getValue());
      }
    }
  }
}
