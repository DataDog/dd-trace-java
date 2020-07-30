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
import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.CONSTANT_TAGS;
import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.NO_CACHING;
import static datadog.trace.core.serialization.msgpack.Util.writeLongAsString;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanData;
import datadog.trace.core.TagsAndBaggageConsumer;
import datadog.trace.core.serialization.msgpack.Writable;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;

public final class TraceMapperV0_4 implements TraceMapper {
  private final byte[] numberByteArray = new byte[20]; // this is max long digits and sign

  @Override
  public void map(List<? extends DDSpanData> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (DDSpanData span : trace) {
      writable.startMap(12);
      /* 1  */
      writable.writeUTF8(SERVICE);
      writable.writeString(span.getServiceName(), CONSTANT_TAGS);
      /* 2  */
      writable.writeUTF8(NAME);
      writable.writeString(span.getOperationName(), CONSTANT_TAGS);
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
      writable.writeString(span.getType(), CONSTANT_TAGS);
      /* 10 */
      writable.writeUTF8(ERROR);
      writable.writeInt(span.getError());
      /* 11 */
      writable.writeUTF8(METRICS);
      writable.writeMap(span.getMetrics(), CONSTANT_KEYS);
      /* 12 */
      writable.writeUTF8(META);
      span.processTagsAndBaggage(
              new TagsAndBaggageConsumer() {
                @Override
                public void accept(Map<String, Object> tags, Map<String, String> baggage) {
                  // since tags can "override" baggage, we need to count the non overlapping ones
                  int size = tags.size();
                  boolean overlap = false;
                  if (baggage.size() > 0) {
                    for (String key : baggage.keySet()) {
                      if (!tags.containsKey(key)) {
                        size++;
                      } else {
                        overlap = true;
                      }
                    }
                  }
                  writable.startMap(size);
                  for (Map.Entry<String, String> entry : baggage.entrySet()) {
                    // tags and baggage may intersect, but tags take priority
                    if (!overlap || !tags.containsKey(entry.getKey())) {
                      writable.writeString(entry.getKey(), CONSTANT_KEYS);
                      writable.writeObject(entry.getValue(), NO_CACHING);
                    }
                  }
                  for (Map.Entry<String, Object> entry : tags.entrySet()) {
                    writable.writeString(entry.getKey(), CONSTANT_KEYS);
                    if (entry.getValue() instanceof Long || entry.getValue() instanceof Integer) {
                      // TODO it would be nice not to need to do this, either because
                      //  the agent would accept variably typed tag values, or numeric
                      //  tags get moved to the metrics
                      writeLongAsString(
                          ((Number) entry.getValue()).longValue(), writable, numberByteArray);
                    } else if (entry.getValue() instanceof UTF8BytesString) {
                      // TODO assess whether this is still worth it
                      writable.writeObject(entry.getValue(), NO_CACHING);
                    } else {
                      writable.writeString(String.valueOf(entry.getValue()), NO_CACHING);
                    }
                  }
                }
              });
    }
  }

  @Override
  public Payload newPayload() {
    return new PayloadV0_4();
  }

  @Override
  public void reset() {}

  private static class PayloadV0_4 extends Payload {

    @Override
    int sizeInBytes() {
      return sizeInBytes(body);
    }

    @Override
    void writeTo(WritableByteChannel channel) throws IOException {
      writeBufferToChannel(body, channel);
    }
  }
}
