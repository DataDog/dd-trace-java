package datadog.json;

import java.util.Collection;
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
      return writer.toString();
    }
  }

  /**
   * Converts a {@link Iterable<String>} to a JSON array.
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
      writer.beginArray();
      for (String item : items) {
        writer.value(item);
      }
      writer.endArray();
      return writer.toString();
    }
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
      writer.beginArray();
      for (String item : items) {
        writer.value(item);
      }
      writer.endArray();
      return writer.toString();
    }
  }
}
