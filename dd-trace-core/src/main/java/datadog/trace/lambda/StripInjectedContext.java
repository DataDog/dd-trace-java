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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strips the injected "_datadog" trace carrier from Lambda event payloads before the user
 * handler runs, when {@code DD_LAMBDA_STRIP_INJECTED_CONTEXT} is enabled. Applies to any
 * top-level field value, covering EventBridge, SNS, SQS, Kinesis, and similar event shapes.
 * Fail-open: any error or invalid result returns the original payload unchanged.
 */
public final class StripInjectedContext {

  private static final Logger log = LoggerFactory.getLogger(StripInjectedContext.class);

  private static final String DATADOG_CARRIER_KEY = "_datadog";

  /** Raw bytes of "_datadog" for the fast-path substring check. */
  private static final byte[] DATADOG_CARRIER_BYTES =
      DATADOG_CARRIER_KEY.getBytes(StandardCharsets.UTF_8);

  /**
   * Byte pattern {@code "_datadog":} used to locate object-form carriers in raw JSON bytes.
   * The colon is included so a single scan step covers both the key and the separator,
   * matching the approach used in the Go tracer implementation.
   */
  private static final byte[] KEY_COLON_BYTES =
      ("\"" + DATADOG_CARRIER_KEY + "\":").getBytes(StandardCharsets.UTF_8);

  /**
   * Byte pattern {@code \"_datadog\"} used to detect carriers that are embedded inside
   * string-encoded JSON fields (e.g. an SNS {@code Message} field).
   */
  private static final byte[] ESCAPED_KEY_BYTES =
      ("\\\"" + DATADOG_CARRIER_KEY + "\\\"").getBytes(StandardCharsets.UTF_8);

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

  /** Strips "_datadog" from {@code payload} when the config is enabled. */
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
    // Fast path: skip all processing when "_datadog" bytes are absent entirely.
    if (indexOf(payload, 0, DATADOG_CARRIER_BYTES) < 0) {
      return payload;
    }
    // Pass 1: splice out "_datadog":{...} object carriers via byte-level scanning.
    // No JSON parsing or object allocation occurs here.
    byte[] result = stripObjectCarriers(payload);
    // Pass 2: strip "_datadog" from string-encoded fields (e.g. SNS Message).
    // Uses Moshi only when the escaped key pattern is present.
    result = stripStringEncodedCarriers(result);
    return result;
  }

  // ============================================================================
  // Object carrier stripping: byte-level splice (mirrors Go's stripObjectCarriers)
  // ============================================================================

  /**
   * Removes all {@code "_datadog":{...}} object-form carriers from {@code msg} by splicing
   * the matching byte ranges out of the array. Returns {@code msg} unchanged if no carriers
   * are found. No JSON parsing or heap allocation occurs unless a carrier is actually present.
   */
  private static byte[] stripObjectCarriers(byte[] msg) {
    ByteArrayOutputStream out = null;
    int searchFrom = 0;
    int copyFrom = 0;

    while (searchFrom < msg.length) {
      int[] carrier = findNextObjectCarrier(msg, searchFrom);
      if (carrier == null) {
        break;
      }
      int keyStart = carrier[0];
      int valueEnd = carrier[1];
      int[] removal = carrierRemovalRange(msg, keyStart, valueEnd, searchFrom);

      if (out == null) {
        // Defer allocation until the first carrier is confirmed. Pre-size to the full
        // input length so that multiple removals do not trigger internal buffer growth.
        out = new ByteArrayOutputStream(msg.length);
      }
      out.write(msg, copyFrom, removal[0] - copyFrom);
      copyFrom = removal[1];
      searchFrom = removal[1];
    }

    if (out == null) {
      return msg;
    }
    out.write(msg, copyFrom, msg.length - copyFrom);
    return out.toByteArray();
  }

  /**
   * Returns {@code [keyStart, valueEnd]} for the next removable {@code "_datadog":{...}}
   * carrier found at or after {@code searchFrom}, or {@code null} if none exists.
   *
   * <p>A carrier is removable only when (1) it is an actual JSON object property, not text
   * embedded inside a string value, and (2) its value is an object (starts with {@code {}).
   */
  private static int[] findNextObjectCarrier(byte[] msg, int searchFrom) {
    while (searchFrom < msg.length) {
      int keyStart = indexOf(msg, searchFrom, KEY_COLON_BYTES);
      if (keyStart < 0) {
        return null;
      }
      if (!isObjectProperty(msg, keyStart)) {
        searchFrom = keyStart + 1;
        continue;
      }
      int valueStart = objectValueStart(msg, keyStart + KEY_COLON_BYTES.length);
      if (valueStart < 0) {
        // Value is not a JSON object (e.g. "_datadog": null); skip this occurrence.
        searchFrom = keyStart + 1;
        continue;
      }
      int valueEnd = scanObjectEnd(msg, valueStart);
      if (valueEnd < 0) {
        // Malformed JSON: opening '{' has no matching '}'; skip safely.
        searchFrom = keyStart + 1;
        continue;
      }
      return new int[]{keyStart, valueEnd};
    }
    return null;
  }

  /**
   * Returns {@code true} when the byte immediately before {@code keyStart} (ignoring JSON
   * whitespace) is {@code {} or {@code ,}, confirming that this is a real JSON property key
   * and not text that happens to appear inside a string value.
   */
  private static boolean isObjectProperty(byte[] msg, int keyStart) {
    int previous = skipWhitespaceBackward(msg, keyStart - 1);
    return previous >= 0 && (msg[previous] == '{' || msg[previous] == ',');
  }

  /**
   * Returns the index of the opening {@code {} of the carrier value (skipping JSON whitespace
   * after the colon), or {@code -1} if the value does not start with {@code {}.
   */
  private static int objectValueStart(byte[] msg, int start) {
    start = skipWhitespaceForward(msg, start);
    return (start < msg.length && msg[start] == '{') ? start : -1;
  }

  /**
   * Returns the {@code [start, end)} byte range to remove for the carrier whose key begins at
   * {@code keyStart} and whose value ends at {@code valueEnd} (exclusive). The range is expanded
   * to consume one adjacent comma so the surrounding JSON remains valid. The preceding comma is
   * preferred unless it falls before {@code searchFrom} (which belongs to a range already removed
   * in an earlier iteration).
   */
  private static int[] carrierRemovalRange(
      byte[] msg, int keyStart, int valueEnd, int searchFrom) {
    int previous = skipWhitespaceBackward(msg, keyStart - 1);
    if (previous >= searchFrom && msg[previous] == ',') {
      return new int[]{previous, valueEnd};
    }
    int next = skipWhitespaceForward(msg, valueEnd);
    if (next < msg.length && msg[next] == ',') {
      return new int[]{keyStart, next + 1};
    }
    // Sole field in its object: remove key and value without any comma.
    return new int[]{keyStart, valueEnd};
  }

  /**
   * Returns the exclusive end index of the JSON object that opens at {@code start}, tracking
   * nested objects and quoted strings (including escape sequences) so that braces inside strings
   * are not counted. Returns {@code -1} if no matching closing brace is found.
   */
  private static int scanObjectEnd(byte[] msg, int start) {
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;

    for (int i = start; i < msg.length; i++) {
      byte c = msg[i];

      if (inString) {
        if (escaped) {
          escaped = false;
          continue;
        }
        if (c == '\\') {
          escaped = true;
          continue;
        }
        if (c == '"') {
          inString = false;
        }
        continue;
      }

      switch (c) {
        case '"': inString = true; break;
        case '{': depth++; break;
        case '}':
          depth--;
          if (depth == 0) {
            return i + 1;
          }
          break;
        default: break;
      }
    }
    return -1;
  }

  // ============================================================================
  // String-encoded carrier stripping: Moshi-based (for SNS Message and similar)
  // ============================================================================

  /**
   * Removes {@code "_datadog"} from every top-level field whose value is a string-encoded JSON
   * object (e.g. an SNS {@code Message} field). Uses Moshi only when {@code \"_datadog\"} is
   * actually present in the bytes. Returns {@code msg} unchanged on any error.
   */
  private static byte[] stripStringEncodedCarriers(byte[] msg) {
    if (indexOf(msg, 0, ESCAPED_KEY_BYTES) < 0) {
      return msg;
    }
    String json = new String(msg, StandardCharsets.UTF_8);
    try {
      Map<String, Object> envelope = MAP_ADAPTER.fromJson(json);
      if (envelope == null) {
        return msg;
      }
      boolean changed = false;
      for (Map.Entry<String, Object> entry : envelope.entrySet()) {
        if (entry.getValue() instanceof String) {
          String stripped = stripFromJsonString((String) entry.getValue());
          if (stripped != null) {
            entry.setValue(stripped);
            changed = true;
          }
        }
      }
      if (!changed) {
        return msg;
      }
      return MAP_ADAPTER.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      log.debug("Unable to strip string-encoded _datadog context from Lambda payload", e);
      return msg;
    }
  }

  // ============================================================================
  // Byte-level utilities
  // ============================================================================

  /**
   * Returns the start index of the first occurrence of {@code needle} in {@code haystack} at or
   * after {@code from}, or {@code -1} if not found.
   */
  private static int indexOf(byte[] haystack, int from, byte[] needle) {
    if (needle.length == 0) {
      return from;
    }
    int limit = haystack.length - needle.length;
    outer:
    for (int i = from; i <= limit; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  /** Returns the first index at or after {@code start} that is not JSON whitespace. */
  private static int skipWhitespaceForward(byte[] msg, int start) {
    while (start < msg.length && isJSONWhitespace(msg[start])) {
      start++;
    }
    return start;
  }

  /**
   * Returns the first index at or before {@code start} that is not JSON whitespace,
   * or a negative value if only whitespace precedes.
   */
  private static int skipWhitespaceBackward(byte[] msg, int start) {
    while (start >= 0 && isJSONWhitespace(msg[start])) {
      start--;
    }
    return start;
  }

  /** Returns {@code true} if {@code c} is a JSON whitespace byte (space, tab, CR, or LF). */
  private static boolean isJSONWhitespace(byte c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
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

    /** Returns Long for integer literals, BigDecimal for fractional/scientific ones. */
    private Object readNumber(JsonReader reader) throws IOException {
      String literal = reader.nextString(); // raw text avoids precision loss before parsing
      if (literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0) {
        // BigDecimal preserves full precision instead of losing digits like Double would.
        return new BigDecimal(literal);
      }
      try {
        return Long.parseLong(literal);
      } catch (NumberFormatException tooLargeForLong) {
        return new BigDecimal(literal);
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
