package datadog.smoketest.springboot;

import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import okio.Buffer;

/**
 * Reads and writes JSON, keeping numbers as written: the evaluation cases tell integers from
 * decimals, which the application maps to different types.
 */
final class Json {
  private Json() {}

  /**
   * Parses a JSON document.
   *
   * @param json The JSON document.
   * @return The parsed value: maps, lists, strings, {@link BigDecimal} numbers, booleans or {@code
   *     null}.
   */
  static Object parse(String json) {
    try (JsonReader reader = JsonReader.of(new Buffer().writeUtf8(json))) {
      return read(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to parse JSON: " + json, e);
    }
  }

  /**
   * Writes a value as a JSON document.
   *
   * @param value The value, as returned by {@link #parse(String)}.
   * @return The JSON document.
   */
  static String write(Object value) {
    Buffer buffer = new Buffer();
    try (JsonWriter writer = JsonWriter.of(buffer)) {
      writer.setSerializeNulls(true);
      write(writer, value);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write JSON", e);
    }
    return buffer.readUtf8();
  }

  /**
   * Compares two JSON values, numbers by value.
   *
   * @param expected The expected value.
   * @param actual The actual value.
   * @return Whether both values are equal.
   */
  static boolean equal(Object expected, Object actual) {
    if (expected instanceof BigDecimal && actual instanceof BigDecimal) {
      return ((BigDecimal) expected).compareTo((BigDecimal) actual) == 0;
    }
    if (expected instanceof Map && actual instanceof Map) {
      Map<?, ?> expectedMap = (Map<?, ?>) expected;
      Map<?, ?> actualMap = (Map<?, ?>) actual;
      if (!expectedMap.keySet().equals(actualMap.keySet())) {
        return false;
      }
      for (Map.Entry<?, ?> entry : expectedMap.entrySet()) {
        if (!equal(entry.getValue(), actualMap.get(entry.getKey()))) {
          return false;
        }
      }
      return true;
    }
    if (expected instanceof List && actual instanceof List) {
      List<?> expectedList = (List<?>) expected;
      List<?> actualList = (List<?>) actual;
      if (expectedList.size() != actualList.size()) {
        return false;
      }
      Iterator<?> actualValues = actualList.iterator();
      for (Object expectedValue : expectedList) {
        if (!equal(expectedValue, actualValues.next())) {
          return false;
        }
      }
      return true;
    }
    return Objects.equals(expected, actual);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Object> array(Object value) {
    return (List<Object>) value;
  }

  private static Object read(JsonReader reader) throws IOException {
    switch (reader.peek()) {
      case BEGIN_OBJECT:
        Map<String, Object> object = new LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
          object.put(reader.nextName(), read(reader));
        }
        reader.endObject();
        return object;
      case BEGIN_ARRAY:
        List<Object> array = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
          array.add(read(reader));
        }
        reader.endArray();
        return array;
      case NUMBER:
        return new BigDecimal(reader.nextString());
      case BOOLEAN:
        return reader.nextBoolean();
      case NULL:
        return reader.nextNull();
      default:
        return reader.nextString();
    }
  }

  private static void write(JsonWriter writer, Object value) throws IOException {
    if (value instanceof Map) {
      writer.beginObject();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        writer.name(String.valueOf(entry.getKey()));
        write(writer, entry.getValue());
      }
      writer.endObject();
    } else if (value instanceof List) {
      writer.beginArray();
      for (Object item : (List<?>) value) {
        write(writer, item);
      }
      writer.endArray();
    } else if (value instanceof Number) {
      writer.value((Number) value);
    } else if (value instanceof Boolean) {
      writer.value((Boolean) value);
    } else if (value == null) {
      writer.nullValue();
    } else {
      writer.value(value.toString());
    }
  }
}
