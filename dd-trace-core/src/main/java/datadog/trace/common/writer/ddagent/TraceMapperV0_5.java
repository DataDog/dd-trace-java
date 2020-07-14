package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.CONSTANT_KEYS;
import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.CONSTANT_TAGS;
import static datadog.trace.core.serialization.msgpack.EncodingCachingStrategies.NO_CACHING;
import static datadog.trace.core.serialization.msgpack.Util.writeLongAsString;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpan;
import datadog.trace.core.serialization.msgpack.Mapper;
import datadog.trace.core.serialization.msgpack.Writable;
import java.util.List;
import java.util.Map;

public final class TraceMapperV0_5 implements Mapper<List<DDSpan>> {

  private final byte[] numberByteArray = new byte[20]; // this is max long digits and sign

  @Override
  public void map(List<DDSpan> trace, Writable writable) {
    writable.startArray(trace.size());
    for (DDSpan span : trace) {
      writable.startArray(12);
      /* 1  */
      writable.writeString(span.getServiceName(), CONSTANT_TAGS);
      /* 2  */
      writable.writeString(span.getOperationName(), CONSTANT_TAGS);
      /* 3  */
      writable.writeObject(span.getResourceName(), NO_CACHING);
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
      /* 9 */
      writable.writeInt(span.getError());
      /* 10 */
      Map<String, String> baggage = span.context().getBaggageItems();
      Map<String, Object> tags = span.context().getTags();
      writable.startMap(baggage.size() + tags.size());
      for (Map.Entry<String, String> entry : baggage.entrySet()) {
        // tags and baggage may intersect, but tags take priority
        if (!tags.containsKey(entry.getKey())) {
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
          writeLongAsString(((Number) entry.getValue()).longValue(), writable, numberByteArray);
        } else if (entry.getValue() instanceof UTF8BytesString) {
          // TODO assess whether this is still worth it
          writable.writeObject(entry.getValue(), NO_CACHING);
        } else {
          writable.writeString(String.valueOf(entry.getValue()), NO_CACHING);
        }
      }
      /* 11 */
      writable.writeMap(span.getMetrics(), CONSTANT_KEYS);
      /* 12  */
      writable.writeString(span.getType(), CONSTANT_TAGS);
    }
  }
}
