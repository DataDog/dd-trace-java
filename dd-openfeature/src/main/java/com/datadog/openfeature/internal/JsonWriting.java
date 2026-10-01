package com.datadog.openfeature.internal;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;

/** JSON serialization helpers for event payloads. */
public final class JsonWriting {
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private JsonWriting() {}

  /**
   * Serializes a JSON document.
   *
   * @param writer the document writer.
   * @return the UTF-8 encoded document.
   */
  public static byte[] write(final Writer writer) {
    final ByteArrayOutputStream output = new ByteArrayOutputStream(256);
    try (JsonGenerator generator = JSON_FACTORY.createGenerator(output, JsonEncoding.UTF8)) {
      writer.write(generator);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return output.toByteArray();
  }

  /**
   * Writes an arbitrary value: maps, collections, strings, numbers, booleans and {@code null}.
   * Other values are written with their string representation.
   *
   * @param generator the generator to write to.
   * @param value the value to write.
   * @throws IOException if the value could not be written.
   */
  public static void writeValue(final JsonGenerator generator, @Nullable final Object value)
      throws IOException {
    if (value == null) {
      generator.writeNull();
    } else if (value instanceof String) {
      generator.writeString((String) value);
    } else if (value instanceof Boolean) {
      generator.writeBoolean((Boolean) value);
    } else if (value instanceof Integer || value instanceof Long || value instanceof Short) {
      generator.writeNumber(((Number) value).longValue());
    } else if (value instanceof Number) {
      generator.writeNumber(((Number) value).doubleValue());
    } else if (value instanceof Map) {
      generator.writeStartObject();
      for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        generator.writeFieldName(String.valueOf(entry.getKey()));
        writeValue(generator, entry.getValue());
      }
      generator.writeEndObject();
    } else if (value instanceof Collection) {
      generator.writeStartArray();
      for (final Object element : (Collection<?>) value) {
        writeValue(generator, element);
      }
      generator.writeEndArray();
    } else {
      generator.writeString(value.toString());
    }
  }

  /**
   * Writes a {@code {"key": ...}} object field, skipped when the key is {@code null}.
   *
   * @param generator the generator to write to.
   * @param fieldName the field name.
   * @param key the key value.
   * @throws IOException if the field could not be written.
   */
  public static void writeKeyObject(
      final JsonGenerator generator, final String fieldName, @Nullable final String key)
      throws IOException {
    if (key != null) {
      generator.writeObjectFieldStart(fieldName);
      generator.writeStringField("key", key);
      generator.writeEndObject();
    }
  }

  /** Writes a JSON document. */
  @FunctionalInterface
  public interface Writer {
    void write(JsonGenerator generator) throws IOException;
  }
}
