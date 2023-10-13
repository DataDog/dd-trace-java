package datadog.trace.test.agent.decoder.v04.raw;

import datadog.trace.test.agent.decoder.DecodedSpan;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.msgpack.core.MessageIntegerOverflowException;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

public class SpanV04 implements DecodedSpan {
  static DecodedSpan[] unpackSpans(MessageUnpacker unpacker) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size < 0) {
        throw new IllegalArgumentException("Negative span array size " + size);
      }
      DecodedSpan[] spans = new DecodedSpan[size];
      for (int i = 0; i < size; i++) {
        spans[i] = unpack(unpacker);
      }
      return spans;
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  static SpanV04 unpack(MessageUnpacker unpacker) {
    try {
      int size = unpacker.unpackMapHeader();
      if (size != 12) {
        throw new IllegalArgumentException(
            "Wrong span element map size " + size + ". Expected 12.");
      }

      String service = unpackString("service", unpacker);
      String name = unpackString("name", unpacker);
      String resource = unpackString("resource", unpacker);
      long traceId = unpackLong("trace_id", unpacker);
      long spanId = unpackLong("span_id", unpacker);
      long parentId = unpackLong("parent_id", unpacker);
      long start = unpackLong("start", unpacker);
      long duration = unpackLong("duration", unpacker);
      String type = unpackString("type", unpacker);
      int error = unpackInt("error", unpacker);

      unpackKey("metrics", unpacker);
      Map<String, Number> metrics = unpackMetrics(unpacker, spanId);

      unpackKey("meta", unpacker);
      Map<String, String> meta = unpackMeta(unpacker, spanId);

      return new SpanV04(
          service, name, resource, traceId, spanId, parentId, start, duration, error, type, metrics,
          meta);
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  private static Map<String, Number> unpackMetrics(MessageUnpacker unpacker, long spanId)
      throws IOException {
    int metricsSize = unpacker.unpackMapHeader();
    if (metricsSize < 0) {
      throw new IllegalArgumentException(
          "Negative meta map size " + metricsSize + " for span " + spanId);
    }
    Map<String, Number> metrics = new HashMap<>(metricsSize);
    for (int i = 0; i < metricsSize; i++) {
      metrics.put(unpacker.unpackString(), unpackNumber(unpacker));
    }
    return metrics;
  }

  private static Map<String, String> unpackMeta(MessageUnpacker unpacker, long spanId)
      throws IOException {
    int metaSize = unpacker.unpackMapHeader();
    if (metaSize < 0) {
      throw new IllegalArgumentException(
          "Negative meta map size " + metaSize + " for span " + spanId);
    }
    Map<String, String> meta = new HashMap<>(metaSize);
    for (int i = 0; i < metaSize; i++) {
      meta.put(unpacker.unpackString(), unpacker.unpackString());
    }
    return meta;
  }

  private static String unpackString(String expectedKey, MessageUnpacker unpacker)
      throws IOException {
    unpackKey(expectedKey, unpacker);
    if (unpacker.tryUnpackNil()) {
      return null;
    }
    return unpacker.unpackString();
  }

  private static long unpackLong(String expectedKey, MessageUnpacker unpacker) throws IOException {
    unpackKey(expectedKey, unpacker);
    return unpacker.unpackLong();
  }

  private static int unpackInt(String expectedKey, MessageUnpacker unpacker) throws IOException {
    unpackKey(expectedKey, unpacker);
    return unpacker.unpackInt();
  }

  private static void unpackKey(String expectedKey, MessageUnpacker unpacker) throws IOException {
    assert expectedKey.equals(unpacker.unpackString());
  }

  static Number unpackNumber(MessageUnpacker unpacker) {
    Number result = null;
    try {
      ValueType valueType = unpacker.getNextFormat().getValueType();
      switch (valueType) {
        case INTEGER:
          try {
            result = unpacker.unpackInt();
          } catch (MessageIntegerOverflowException e) {
            result = unpacker.unpackLong();
          }
          break;
        case FLOAT:
          result = unpacker.unpackDouble();
          break;
        default:
          throw new IllegalArgumentException(
              "Failed to decode number. Unexpected value type " + valueType);
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to decode number.", e);
    }
    return result;
  }

  private final String service;
  private final String name;
  private final String resource;
  private final long traceId;
  private final long spanId;
  private final long parentId;
  private final long start;
  private final long duration;
  private final int error;
  private final Map<String, String> meta;
  private final Map<String, Number> metrics;
  private final String type;

  public SpanV04(
      String service,
      String name,
      String resource,
      long traceId,
      long spanId,
      long parentId,
      long start,
      long duration,
      int error,
      String type,
      Map<String, Number> metrics,
      Map<String, String> meta) {
    this.service = service;
    this.name = name;
    this.resource = resource;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentId = parentId;
    this.start = start;
    this.duration = duration;
    this.error = error;
    this.meta = Collections.unmodifiableMap(meta);
    this.metrics = Collections.unmodifiableMap(metrics);
    this.type = type;
  }

  public String getService() {
    return service;
  }

  public String getName() {
    return name;
  }

  public String getResource() {
    return resource;
  }

  public long getTraceId() {
    return traceId;
  }

  public long getSpanId() {
    return spanId;
  }

  public long getParentId() {
    return parentId;
  }

  public long getStart() {
    return start;
  }

  public long getDuration() {
    return duration;
  }

  public int getError() {
    return error;
  }

  public Map<String, String> getMeta() {
    return meta;
  }

  public Map<String, Number> getMetrics() {
    return metrics;
  }

  public String getType() {
    return type;
  }

  @Override
  public String toString() {
    return "SpanV04{"
        + "service='"
        + service
        + '\''
        + ", name='"
        + name
        + '\''
        + ", resource='"
        + resource
        + '\''
        + ", traceId="
        + traceId
        + ", spanId="
        + spanId
        + ", parentId="
        + parentId
        + ", start="
        + start
        + ", duration="
        + duration
        + ", error="
        + error
        + ", meta="
        + meta
        + ", metrics="
        + metrics
        + ", type='"
        + type
        + '\''
        + '}';
  }
}
