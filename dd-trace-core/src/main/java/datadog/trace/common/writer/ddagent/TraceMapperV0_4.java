package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.StringTables.DURATION;
import static datadog.trace.core.StringTables.ERROR;
import static datadog.trace.core.StringTables.META;
import static datadog.trace.core.StringTables.METRICS;
import static datadog.trace.core.StringTables.NAME;
import static datadog.trace.core.StringTables.PARENT_ID;
import static datadog.trace.core.StringTables.RESOURCE;
import static datadog.trace.core.StringTables.SERVICE;
import static datadog.trace.core.StringTables.SPAN_ID;
import static datadog.trace.core.StringTables.START;
import static datadog.trace.core.StringTables.TRACE_ID;
import static datadog.trace.core.StringTables.TYPE;
import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.CONSTANT_KEYS;
import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.NO_CACHING;
import static datadog.trace.core.serialization.msgpack.Util.integerToStringBuffer;
import static datadog.trace.core.serialization.msgpack.Util.writeLongAsString;

import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanData;
import datadog.trace.core.TagsAndBaggageConsumer;
import datadog.trace.core.serialization.msgpack.Writable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;

public final class TraceMapperV0_4 implements TraceMapper {

  static final byte[] EMPTY = ByteBuffer.allocate(1).put((byte) 0x90).array();

  private static final class MetaWriter extends TagsAndBaggageConsumer {

    private final byte[] numberByteArray = integerToStringBuffer();
    private Writable writable;

    MetaWriter withWritable(Writable writable) {
      this.writable = writable;
      return this;
    }

    @Override
    public void accept(Map<String, Object> tags, Map<String, String> baggage) {
      // since tags can "override" baggage, we need to count the non overlapping ones
      int size = tags.size();
      // assume we can't have more than 64 baggage items,
      // and that iteration order is stable to avoid looking
      // up in the tags more than necessary
      long overlaps = 0L;
      if (!baggage.isEmpty()) {
        int i = 0;
        for (Map.Entry<String, String> key : baggage.entrySet()) {
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
      for (Map.Entry<String, String> entry : baggage.entrySet()) {
        // tags and baggage may intersect, but tags take priority
        if ((overlaps & (1L << i)) == 0) {
          writable.writeString(entry.getKey(), CONSTANT_KEYS);
          writable.writeString(entry.getValue(), NO_CACHING);
        }
        ++i;
      }
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        writable.writeString(entry.getKey(), CONSTANT_KEYS);
        if (entry.getValue() instanceof Long || entry.getValue() instanceof Integer) {
          // TODO it would be nice not to need to do this, either because
          //  the agent would accept variably typed tag values, or numeric
          //  tags get moved to the metrics
          writeLongAsString(((Number) entry.getValue()).longValue(), writable, numberByteArray);
        } else if (entry.getValue() instanceof UTF8BytesString) {
          writable.writeUTF8((UTF8BytesString) entry.getValue());
        } else {
          writable.writeString(String.valueOf(entry.getValue()), NO_CACHING);
        }
      }
    }
  }

  private final MetaWriter metaWriter = new MetaWriter();

  @Override
  public void map(List<? extends DDSpanData> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (DDSpanData span : trace) {
      writable.startMap(12);
      /* 1  */
      writable.writeUTF8(SERVICE);
      writable.writeString(span.getServiceName(), NO_CACHING);
      /* 2  */
      writable.writeUTF8(NAME);
      writable.writeObject(span.getOperationName(), NO_CACHING);
      /* 3  */
      writable.writeUTF8(RESOURCE);
      writable.writeObject(span.getResourceName(), NO_CACHING);
      /* 4  */
      writable.writeUTF8(TRACE_ID);
      writable.writeLong(span.getTraceId().toLong());
      /* 5  */
      writable.writeUTF8(SPAN_ID);
      writable.writeLong(span.getSpanId().toLong());
      /* 6  */
      writable.writeUTF8(PARENT_ID);
      writable.writeLong(span.getParentId().toLong());
      /* 7  */
      writable.writeUTF8(START);
      writable.writeLong(span.getStartTime());
      /* 8  */
      writable.writeUTF8(DURATION);
      writable.writeLong(span.getDurationNano());
      /* 9  */
      writable.writeUTF8(TYPE);
      writable.writeString(span.getType(), NO_CACHING);
      /* 10 */
      writable.writeUTF8(ERROR);
      writable.writeInt(span.getError());
      /* 11 */
      writeMetrics(span, writable);
      /* 12 */
      writable.writeUTF8(META);
      span.processTagsAndBaggage(metaWriter.withWritable(writable));
    }
  }

  private static void writeMetrics(DDSpanData span, Writable writable) {
    writable.writeUTF8(METRICS);
    Map<CharSequence, Number> metrics = span.getMetrics();
    int elementCount = metrics.size() + (span.isMeasured() ? 1 : 0);
    writable.startMap(elementCount);
    if (span.isMeasured()) {
      writable.writeUTF8(InstrumentationTags.DD_MEASURED);
      writable.writeInt(1);
    }
    for (Map.Entry<CharSequence, Number> metric : metrics.entrySet()) {
      writable.writeString(metric.getKey(), CONSTANT_KEYS);
      writable.writeObject(metric.getValue(), NO_CACHING);
    }
  }

  @Override
  public Payload newPayload() {
    return new PayloadV0_4();
  }

  @Override
  public int messageBufferSize() {
    return 5 << 20; // 5MB
  }

  @Override
  public void reset() {}

  @Override
  public String endpoint() {
    return "v0.4";
  }

  private static class PayloadV0_4 extends Payload {

    @Override
    int sizeInBytes() {
      return sizeInBytes(body);
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      writeBufferToChannel(body, channel);
    }
  }
}
