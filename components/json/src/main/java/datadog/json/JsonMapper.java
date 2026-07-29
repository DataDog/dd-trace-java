package datadog.json;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Utility class for simple Java structure mapping into JSON strings. */
public final class JsonMapper {

  private JsonMapper() {}

  /**
   * Converts a {@link String} to a JSON string.
   *
   * @param string The string to convert.
   * @return The converted JSON string.
   */
  public static String toJson(String string) {
    if (string == null || string.isEmpty()) {
      return "";
    }
    try (JsonWriter writer = new JsonWriter()) {
      writer.value(string);
      return writer.toString();
    }
  }

  /**
   * Converts a {@link Map} to a JSON object.
   *
   * @param map The map to convert.
   * @return The converted JSON object as Java string.
   */
  public static String toJson(Map<String, ?> map) {
    if (map == null || map.isEmpty()) {
      return "{}";
    }
    try (JsonWriter writer = new JsonWriter()) {
      writeMap(writer, map);
      return writer.toString();
    }
  }

  /**
   * Writes the map as JSON value to the given mapper.
   *
   * @param writer The writer to write the map as value to.
   * @param map The map to write.
   */
  public static void writeAsJsonValue(JsonWriter writer, Map<String, ?> map) {
    if (writer == null) {
      throw new NullPointerException("writer cannot be null");
    }
    if (map == null) {
      writer.beginObject();
      writer.endObject();
    } else {
      writeMap(writer, map);
    }
  }

  private static void writeMap(JsonWriter writer, Map<String, ?> map) {
    writer.beginObject();
    for (Map.Entry<String, ?> entry : map.entrySet()) {
      writer.name(entry.getKey());
      Object value = entry.getValue();
      if (value == null) {
        writer.nullValue();
      } else if (value instanceof String) {
        writer.value((String) value);
      } else if (value instanceof Double) {
        writer.value((Double) value);
      } else if (value instanceof Float) {
        writer.value((Float) value);
      } else if (value instanceof Long) {
        writer.value((Long) value);
      } else if (value instanceof Integer) {
        writer.value((Integer) value);
      } else if (value instanceof Boolean) {
        writer.value((Boolean) value);
      } else {
        writer.value(value.toString());
      }
    }
    writer.endObject();
  }

  /**
   * Converts a {@code Collection<String>} to a JSON array.
   *
   * @param items The iterable to convert.
   * @return The converted JSON array as Java string.
   */
  @SuppressWarnings("DuplicatedCode")
  public static String toJson(Collection<String> items) {
    if (items == null || items.isEmpty()) {
      return "[]";
    }
    try (JsonWriter writer = new JsonWriter()) {
      writeArray(items, writer);
      return writer.toString();
    }
  }

  /**
   * Writes the {@code Collection<String>} as a JSON array to the given writer.
   *
   * @param items The collection to write.
   * @param writer The writer to write the collection as JSON array to.
   */
  public static void writeAsJsonValue(Collection<String> items, JsonWriter writer) {
    if (writer == null) {
      throw new NullPointerException("writer cannot be null");
    }
    if (items == null) {
      writer.beginArray();
      writer.endArray();
    } else {
      writeArray(items, writer);
    }
  }

  private static void writeArray(Iterable<String> items, JsonWriter writer) {
    writer.beginArray();
    for (String item : items) {
      writer.value(item);
    }
    writer.endArray();
  }

  /**
   * Converts a String array to a JSON array.
   *
   * @param items The array to convert.
   * @return The converted JSON array as Java string.
   */
  @SuppressWarnings("DuplicatedCode")
  public static String toJson(String[] items) {
    if (items == null) {
      return "[]";
    }
    try (JsonWriter writer = new JsonWriter()) {
      writeArray(items, writer);
      return writer.toString();
    }
  }

  /**
   * Writes the String array as a JSON array to the given writer.
   *
   * @param items The array to write.
   * @param writer The writer to write the array as JSON array to.
   */
  public static void writeAsJsonValue(String[] items, JsonWriter writer) {
    if (writer == null) {
      throw new NullPointerException("writer cannot be null");
    }
    if (items == null) {
      writer.beginArray();
      writer.endArray();
    } else {
      writeArray(items, writer);
    }
  }

  private static void writeArray(String[] items, JsonWriter writer) {
    writer.beginArray();
    for (String item : items) {
      writer.value(item);
    }
    writer.endArray();
  }

  /**
   * Parses a JSON string into a {@link Map}.
   *
   * @param json The JSON string to parse.
   * @return A {@link Map} containing the parsed JSON object's key-value pairs.
   * @throws IOException If the JSON is invalid or a reader error occurs.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> fromJsonToMap(String json) throws IOException {
    if (json == null || json.isEmpty() || "{}".equals(json) || "null".equals(json)) {
      return emptyMap();
    }
    try (JsonReader reader = new JsonReader(json)) {
      Object value = reader.nextValue();
      if (!(value instanceof Map)) {
        throw new IOException("Expected JSON object but was " + value.getClass().getSimpleName());
      }
      return (Map<String, Object>) value;
    }
  }

  /**
   * Parses a JSON string array into a {@code List<String>}.
   *
   * @param json The JSON string array to parse.
   * @return A {@code List<String>} containing the parsed JSON strings.
   * @throws IOException If the JSON is invalid or a reader error occurs.
   */
  public static List<String> fromJsonToList(String json) throws IOException {
    if (json == null || json.isEmpty() || "[]".equals(json) || "null".equals(json)) {
      return emptyList();
    }
    try (JsonReader reader = new JsonReader(json)) {
      List<String> list = new ArrayList<>();
      reader.beginArray();
      while (reader.hasNext()) {
        list.add(reader.nextString());
      }
      reader.endArray();
      return list;
    }
  }
}
