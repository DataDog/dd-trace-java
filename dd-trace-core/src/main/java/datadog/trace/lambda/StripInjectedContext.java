package datadog.trace.lambda;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strips the injected "_datadog" trace carrier from a Lambda event's "detail" field before the user
 * handler runs, when {@code DD_LAMBDA_STRIP_INJECTED_CONTEXT} is enabled. Fail-open: any error or
 * invalid result returns the original payload unchanged.
 */
public final class StripInjectedContext {

  private static final Logger log = LoggerFactory.getLogger(StripInjectedContext.class);

  private static final String DETAIL_FIELD = "detail";
  private static final String DATADOG_CARRIER_KEY = "_datadog";

  // Custom Object adapter preserves Long (not Double) for whole numbers and key order via
  // LinkedHashMap, so re-serializing the envelope doesn't corrupt untouched fields.
  private static final Moshi MOSHI =
      new Moshi.Builder().add(Object.class, new NumberPreservingObjectAdapter()).build();

  private static final Type STRING_OBJECT_MAP_TYPE =
      Types.newParameterizedType(Map.class, String.class, Object.class);

  @SuppressWarnings("unchecked")
  private static final JsonAdapter<Map<String, Object>> MAP_ADAPTER =
      (JsonAdapter<Map<String, Object>>) (JsonAdapter<?>) MOSHI.adapter(STRING_OBJECT_MAP_TYPE);

  private StripInjectedContext() {}

  /** Reads {@code in} fully, strips "_datadog", and returns a new stream over the result. */
  public static ByteArrayInputStream replaceInputStream(InputStream in) {
    if (in == null) {
      return null;
    }
    try {
      return new ByteArrayInputStream(strip(readAllBytes(in)));
    } catch (IOException e) {
      log.debug("Unable to read Lambda payload for _datadog stripping", e);
      return null;
    }
  }

  /** Strips "_datadog" from {@code payload}'s "detail" field when the config is enabled. */
  public static byte[] strip(byte[] payload) {
    if (!Config.get().isLambdaStripInjectedContextEnabled()) {
      return payload;
    }
    return stripInternal(payload);
  }

  /** Unconditional strip logic, package-private so tests can call it directly. */
  static byte[] stripInternal(byte[] payload) {
    if (payload == null || payload.length == 0) {
      return payload;
    }

    // Fast path: skip JSON parsing when there's clearly nothing to strip.
    String json = new String(payload, StandardCharsets.UTF_8);
    if (!json.contains(DATADOG_CARRIER_KEY)) {
      return payload;
    }

    try {
      Map<String, Object> envelope = MAP_ADAPTER.fromJson(json);
      if (envelope == null || !envelope.containsKey(DETAIL_FIELD)) {
        return payload;
      }

      Object strippedDetail = stripDetail(envelope.get(DETAIL_FIELD));
      if (strippedDetail == null) {
        return payload; // no "_datadog" found in detail
      }

      envelope.put(DETAIL_FIELD, strippedDetail);
      return MAP_ADAPTER.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      // Fail open: stripping must never break the handler invocation.
      log.debug("Unable to strip injected _datadog context from Lambda payload", e);
      return payload;
    }
  }

  /** Removes "_datadog" from an object or string-encoded detail value. Null if unchanged. */
  @SuppressWarnings("unchecked")
  private static Object stripDetail(Object detail) {
    // Object detail: "detail": {"foo": "bar", "_datadog": {...}}
    if (detail instanceof Map) {
      Map<String, Object> detailMap = (Map<String, Object>) detail;
      return detailMap.remove(DATADOG_CARRIER_KEY) != null ? detailMap : null;
    }
    // String-encoded detail: "detail": "{\"foo\":\"bar\",\"_datadog\":{...}}"
    if (detail instanceof String) {
      return stripFromJsonString((String) detail);
    }
    return null;
  }

  /** Parses, strips "_datadog", and re-encodes a JSON-object-shaped string. Null if unchanged. */
  private static String stripFromJsonString(String value) {
    if (!value.contains(DATADOG_CARRIER_KEY)) {
      return null;
    }
    try {
      Map<String, Object> inner = MAP_ADAPTER.fromJson(value);
      if (inner == null || inner.remove(DATADOG_CARRIER_KEY) == null) {
        return null;
      }
      return MAP_ADAPTER.toJson(inner);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** Reads all bytes from {@code in}, restoring its position first if it's a byte stream. */
  private static byte[] readAllBytes(InputStream in) throws IOException {
    if (in instanceof ByteArrayInputStream) {
      // mark/reset instead of draining, in case the stream is read again elsewhere.
      ByteArrayInputStream bais = (ByteArrayInputStream) in;
      bais.mark(Integer.MAX_VALUE);
      byte[] bytes = new byte[bais.available()];
      int read = bais.read(bytes);
      bais.reset();
      return (read == bytes.length) ? bytes : Arrays.copyOf(bytes, Math.max(read, 0));
    }
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int n;
    while ((n = in.read(chunk)) != -1) {
      buffer.write(chunk, 0, n);
    }
    return buffer.toByteArray();
  }

  /** Object adapter that keeps whole numbers as {@link Long} and preserves key order. */
  private static final class NumberPreservingObjectAdapter extends JsonAdapter<Object> {

    /** Recursively reads a JSON value, mapping numbers to Long/Double based on their form. */
    @Override
    public Object fromJson(JsonReader reader) throws IOException {
      switch (reader.peek()) {
        case BEGIN_ARRAY:
          List<Object> list = new ArrayList<>();
          reader.beginArray();
          while (reader.hasNext()) {
            list.add(fromJson(reader));
          }
          reader.endArray();
          return list;

        case BEGIN_OBJECT:
          Map<String, Object> map = new LinkedHashMap<>();
          reader.beginObject();
          while (reader.hasNext()) {
            map.put(reader.nextName(), fromJson(reader));
          }
          reader.endObject();
          return map;

        case STRING:
          return reader.nextString();

        case NUMBER:
          return readNumber(reader);

        case BOOLEAN:
          return reader.nextBoolean();

        case NULL:
          return reader.nextNull();

        default:
          throw new IOException("Unexpected JSON token: " + reader.peek());
      }
    }

    /** Returns Long for integer literals, Double for fractional/scientific ones. */
    private Object readNumber(JsonReader reader) throws IOException {
      String literal = reader.nextString(); // raw text avoids precision loss before parsing
      if (literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0) {
        return Double.parseDouble(literal);
      }
      try {
        return Long.parseLong(literal);
      } catch (NumberFormatException tooLargeForLong) {
        return Double.parseDouble(literal);
      }
    }

    /** Recursively writes a value produced by {@link #fromJson}. */
    @Override
    public void toJson(JsonWriter writer, Object value) throws IOException {
      if (value instanceof Map) {
        writer.beginObject();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
          writer.name(String.valueOf(entry.getKey()));
          toJson(writer, entry.getValue());
        }
        writer.endObject();
      } else if (value instanceof List) {
        writer.beginArray();
        for (Object item : (List<?>) value) {
          toJson(writer, item);
        }
        writer.endArray();
      } else if (value instanceof String) {
        writer.value((String) value);
      } else if (value instanceof Long) {
        writer.value(((Long) value).longValue());
      } else if (value instanceof Number) {
        writer.value((Number) value);
      } else if (value instanceof Boolean) {
        writer.value((Boolean) value);
      } else if (value == null) {
        writer.nullValue();
      } else {
        throw new IOException("Unsupported value type: " + value.getClass());
      }
    }
  }
}
