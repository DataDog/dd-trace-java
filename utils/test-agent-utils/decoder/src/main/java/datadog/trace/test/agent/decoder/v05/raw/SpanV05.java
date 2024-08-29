package datadog.trace.test.agent.decoder.v05.raw;

import datadog.trace.test.agent.decoder.DecodedSpan;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.msgpack.core.MessageIntegerOverflowException;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

public class SpanV05 implements DecodedSpan {
  static DecodedSpan[] unpackSpans(MessageUnpacker unpacker, DictionaryV05 dictionary) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size < 0) {
        throw new IllegalArgumentException("Negative span array size " + size);
      }
      DecodedSpan[] spans = new DecodedSpan[size];
      for (int i = 0; i < size; i++) {
        spans[i] = unpack(unpacker, dictionary);
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

  static SpanV05 unpack(MessageUnpacker unpacker, DictionaryV05 dictionary) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size != 12) {
        throw new IllegalArgumentException(
            "Wrong span element array size " + size + ". Expected 12.");
      }
      String service = unpackString(unpacker, dictionary); // index into dictionary
      String name = unpackString(unpacker, dictionary); // index into dictionary
      String resource = unpackString(unpacker, dictionary); // index into dictionary
      long traceId = unpacker.unpackLong();
      long spanId = unpacker.unpackLong();
      long parentId = unpacker.unpackLong();
      long start = unpacker.unpackLong();
      long duration = unpacker.unpackLong();
      int error = unpacker.unpackInt();
      int metaSize = unpacker.unpackMapHeader();
      if (metaSize < 0) {
        throw new IllegalArgumentException(
            "Negative meta map size " + metaSize + " for span " + spanId);
      }
      Map<String, String> meta = new HashMap<>(metaSize);
      for (int i = 0; i < metaSize; i++) {
        meta.put(unpackString(unpacker, dictionary), unpackString(unpacker, dictionary));
      }
      int metricsSize = unpacker.unpackMapHeader();
      if (metricsSize < 0) {
        throw new IllegalArgumentException(
            "Negative metrics map size " + metaSize + " for span " + spanId);
      }
      Map<String, Number> metrics = new HashMap<>(metricsSize);
      for (int i = 0; i < metricsSize; i++) {
        metrics.put(unpackString(unpacker, dictionary), unpackNumber(unpacker));
      }
      String type = unpackString(unpacker, dictionary); // index into dictionary

      return new SpanV05(
          service, name, resource, traceId, spanId, parentId, start, duration, error, meta, metrics,
          type);
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  static String unpackString(MessageUnpacker unpacker, DictionaryV05 dictionary)
      throws IOException {
    return dictionary.at(unpacker.unpackInt());
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

  private final String service; // index into dictionary
  private final String name; // index into dictionary
  private final String resource; // index into dictionary
  private final long traceId;
  private final long spanId;
  private final long parentId;
  private final long start;
  private final long duration;
  private final int error;
  private final Map<String, String> meta; // index -> index
  private final Map<String, Number> metrics; // index -> metric
  private final String type; // index into dictionary

  public SpanV05(
      String service,
      String name,
      String resource,
      long traceId,
      long spanId,
      long parentId,
      long start,
      long duration,
      int error,
      Map<String, String> meta,
      Map<String, Number> metrics,
      String type) {
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

  public Map<String, Object> getMetaStruct() {
    // XXX: meta_struct is not supported in v0.5.
    return null;
  }

  public Map<String, Number> getMetrics() {
    return metrics;
  }

  public String getType() {
    return type;
  }

  @Override
  public String toString() {
    return "SpanV05{"
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
