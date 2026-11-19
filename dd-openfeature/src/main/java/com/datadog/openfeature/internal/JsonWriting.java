package com.datadog.openfeature.internal;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import datadog.trace.util.ChunkedBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
   * Serializes a JSON document into a buffer, as one message with whatever the caller wrote since
   * the last message, such as a separator.
   *
   * @param buffer the buffer to write to.
   * @param writer the document writer.
   * @return whether the message fit; if not, the buffer is back at its last message.
   */
  public static boolean write(final ChunkedBuffer buffer, final Writer writer) {
    try (JsonGenerator generator =
        JSON_FACTORY.createGenerator(new ChunkedOutput(buffer), JsonEncoding.UTF8)) {
      writer.write(generator);
    } catch (final IOException e) {
      buffer.rewind();
      throw new UncheckedIOException(e);
    } catch (final RuntimeException e) {
      buffer.rewind();
      throw e;
    }
    return buffer.mark();
  }

  /** Writes into a buffer: the generator flushes its own buffer into it. */
  static final class ChunkedOutput extends OutputStream {
    private final ChunkedBuffer buffer;

    ChunkedOutput(final ChunkedBuffer buffer) {
      this.buffer = buffer;
    }

    @Override
    public void write(final int b) {
      buffer.put((byte) b);
    }

    @Override
    public void write(final byte[] bytes, final int offset, final int length) {
      buffer.put(bytes, offset, length);
    }
  }

  /**
   * Writes an arbitrary value: maps, collections, strings, numbers, booleans and {@code null}.
   * Null-valued map entries are omitted; null collection elements are preserved. Other values are
   * written with their string representation.
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
        final Object entryValue = entry.getValue();
        if (entryValue != null) {
          generator.writeFieldName(String.valueOf(entry.getKey()));
          writeValue(generator, entryValue);
        }
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
