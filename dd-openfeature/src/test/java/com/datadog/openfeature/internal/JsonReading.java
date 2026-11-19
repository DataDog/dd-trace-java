package com.datadog.openfeature.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads JSON documents as generic trees for test assertions. */
public final class JsonReading {
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private JsonReading() {}

  /** Reads a JSON document. Integral numbers are read as {@link Long}, others as {@link Double}. */
  public static Object read(final byte[] json) {
    try (JsonParser parser = JSON_FACTORY.createParser(json)) {
      parser.nextToken();
      return readValue(parser);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Object read(final String json) {
    return read(json.getBytes(UTF_8));
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> readObject(final byte[] json) {
    return (Map<String, Object>) read(json);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> readObject(final String json) {
    return (Map<String, Object>) read(json);
  }

  /** Reads a JSON object with all numbers as {@link Double}, like Moshi's generic adapters. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> readObjectWithDoubles(final byte[] json) {
    return (Map<String, Object>) toDoubles(read(json));
  }

  private static Object toDoubles(final Object value) {
    if (value instanceof Long) {
      return ((Long) value).doubleValue();
    }
    if (value instanceof Map) {
      final Map<String, Object> map = new LinkedHashMap<>();
      ((Map<?, ?>) value).forEach((k, v) -> map.put((String) k, toDoubles(v)));
      return map;
    }
    if (value instanceof List) {
      final List<Object> list = new ArrayList<>();
      ((List<?>) value).forEach(v -> list.add(toDoubles(v)));
      return list;
    }
    return value;
  }

  private static Object readValue(final JsonParser parser) throws IOException {
    final JsonToken token = parser.currentToken();
    switch (token) {
      case START_OBJECT:
        final Map<String, Object> object = new LinkedHashMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
          final String name = parser.currentName();
          parser.nextToken();
          object.put(name, readValue(parser));
        }
        return object;
      case START_ARRAY:
        final List<Object> array = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          array.add(readValue(parser));
        }
        return array;
      case VALUE_STRING:
        return parser.getText();
      case VALUE_NUMBER_INT:
        return parser.getLongValue();
      case VALUE_NUMBER_FLOAT:
        return parser.getDoubleValue();
      case VALUE_TRUE:
        return Boolean.TRUE;
      case VALUE_FALSE:
        return Boolean.FALSE;
      case VALUE_NULL:
        return null;
      default:
        throw new IOException("Unexpected token " + token);
    }
  }
}
