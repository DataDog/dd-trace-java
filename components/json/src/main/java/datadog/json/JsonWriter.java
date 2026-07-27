package datadog.json;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStreamWriter;

/**
 * A lightweight JSON writer without dependencies. It performs minimal JSON structure checks unless
 * using the lenient mode.
 */
public final class JsonWriter implements Flushable, AutoCloseable {
  private static final int INITIAL_CAPACITY = 256;
  private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
  private final ByteArrayOutputStream outputStream;
  private final OutputStreamWriter writer;
  private final JsonStructure structure;

  private boolean requireComma;
  private int bytesWritten;

  /** Creates a writer with structure check. */
  public JsonWriter() {
    this(true);
  }

  /**
   * Creates a writer.
   *
   * @param safe {@code true} to use safe structure check, {@code false} for lenient mode.
   */
  public JsonWriter(boolean safe) {
    this.outputStream = new ByteArrayOutputStream(INITIAL_CAPACITY);
    this.writer = new OutputStreamWriter(this.outputStream, UTF_8);
    this.structure = safe ? new SafeJsonStructure() : new LenientJsonStructure();
    this.requireComma = false;
  }

  /**
   * Starts a JSON object.
   *
   * @return This writer instance.
   */
  public JsonWriter beginObject() {
    this.structure.beginObject();
    injectCommaIfNeeded();
    write('{');
    return this;
  }

  /**
   * * Ends the current JSON object.
   *
   * @return This writer.
   */
  public JsonWriter endObject() {
    this.structure.endObject();
    write('}');
    endsValue();
    return this;
  }

  /**
   * Writes an object property name.
   *
   * @param name The property name.
   * @return This writer.
   */
  public JsonWriter name(String name) {
    if (name == null) {
      throw new IllegalArgumentException("name cannot be null");
    }
    this.structure.addName();
    injectCommaIfNeeded();
    writeStringLiteral(name);
    write(':');
    return this;
  }

  /**
   * Writes a {@code null} value.
   *
   * @return This writer.
   */
  public JsonWriter nullValue() {
    this.structure.addValue();
    injectCommaIfNeeded();
    writeStringRaw("null");
    endsValue();
    return this;
  }

  /**
   * Writes JSON value without escaping it.
   *
   * @param value The JSON value to write.
   * @return This writer.
   */
  public JsonWriter jsonValue(String value) {
    // No structure check here assuming raw JSON is safe to write
    injectCommaIfNeeded();
    writeStringRaw(value);
    endsValue();
    return this;
  }

  /**
   * Writes a boolean value.
   *
   * @param value The value to write.
   * @return This writer.
   */
  public JsonWriter value(boolean value) {
    this.structure.addValue();
    injectCommaIfNeeded();
    writeStringRaw(value ? "true" : "false");
    endsValue();
    return this;
  }

  /**
   * Writes a string value.
   *
   * @param value The value to write.
   * @return This writer.
   */
  public JsonWriter value(String value) {
    if (value == null) {
      return nullValue();
    }
    this.structure.addValue();
    injectCommaIfNeeded();
    writeStringLiteral(value);
    endsValue();
    return this;
  }

  /**
   * Writes an integer as a number value.
   *
   * @param value The integer to write.
   * @return This writer.
   */
  public JsonWriter value(int value) {
    this.structure.addValue();
    injectCommaIfNeeded();
    writeStringRaw(Integer.toString(value));
    endsValue();
    return this;
  }

  /**
   * Writes a long as a number value.
   *
   * @param value The long to write.
   * @return This writer.
   */
  public JsonWriter value(long value) {
    this.structure.addValue();
    injectCommaIfNeeded();
    writeStringRaw(Long.toString(value));
    endsValue();
    return this;
  }

  /**
   * Writes a float as a number value.
   *
   * @param value The float to write.
   * @return This writer.
   */
  public JsonWriter value(float value) {
    if (Float.isNaN(value)) {
      return nullValue();
    }
    this.structure.addValue();
    injectCommaIfNeeded();
    writeStringRaw(Float.toString(value));
    endsValue();
    return this;
  }

  /**
   * Writes a double as a number value.
   *
   * @param value The value to write.
   * @return This writer.
   */
  public JsonWriter value(double value) {
    if (Double.isNaN(value)) {
      return nullValue();
    }
    this.structure.addValue();
    injectCommaIfNeeded();
    writeStringRaw(Double.toString(value));
    endsValue();
    return this;
  }

  /**
   * Starts a JSON array.
   *
   * @return This writer.
   */
  public JsonWriter beginArray() {
    this.structure.beginArray();
    injectCommaIfNeeded();
    write('[');
    return this;
  }

  /**
   * Ends the current JSON array.
   *
   * @return This writer.
   */
  public JsonWriter endArray() {
    this.structure.endArray();
    endsValue();
    write(']');
    return this;
  }

  /**
   * Gets the JSON String as a UTF-8 byte array.
   *
   * @return The JSON String as a UTF-8 byte array.
   */
  public byte[] toByteArray() {
    flush();
    return this.outputStream.toByteArray();
  }

  @Override
  public String toString() {
    return new String(toByteArray(), UTF_8);
  }

  @Override
  public void flush() {
    try {
      this.writer.flush();
    } catch (IOException ignored) {
    }
  }

  /** Approximate number of bytes written so far. */
  public int sizeInBytes() {
    return this.bytesWritten;
  }

  @Override
  public void close() {
    try {
      this.outputStream.close();
      this.writer.close();
    } catch (IOException ignored) {
    }
  }

  private void injectCommaIfNeeded() {
    if (this.requireComma) {
      write(',');
    }
    this.requireComma = false;
  }

  private void endsValue() {
    this.requireComma = true;
  }

  private void write(char ch) {
    try {
      this.writer.write(ch);
      this.bytesWritten++;
    } catch (IOException ignored) {
    }
  }

  private void writeStringLiteral(String str) {
    try {
      this.writer.write('"');
      int count = 1;

      for (int i = 0; i < str.length(); ++i) {
        char c = str.charAt(i);
        // Escape any char outside ASCII to their Unicode equivalent
        if (c > 127) {
          this.writer.write('\\');
          this.writer.write('u');
          this.writer.write(HEX_DIGITS[(c >>> 12) & 0xF]);
          this.writer.write(HEX_DIGITS[(c >>> 8) & 0xF]);
          this.writer.write(HEX_DIGITS[(c >>> 4) & 0xF]);
          this.writer.write(HEX_DIGITS[c & 0xF]);
          count += 6;
        } else {
          switch (c) {
            case '"': // Quotation mark
            case '\\': // Reverse solidus
            case '/': // Solidus
              this.writer.write('\\');
              this.writer.write(c);
              count += 2;
              break;
            case '\b': // Backspace
              this.writer.write('\\');
              this.writer.write('b');
              count += 2;
              break;
            case '\f': // Form feed
              this.writer.write('\\');
              this.writer.write('f');
              count += 2;
              break;
            case '\n': // Line feed
              this.writer.write('\\');
              this.writer.write('n');
              count += 2;
              break;
            case '\r': // Carriage return
              this.writer.write('\\');
              this.writer.write('r');
              count += 2;
              break;
            case '\t': // Horizontal tab
              this.writer.write('\\');
              this.writer.write('t');
              count += 2;
              break;
            default:
              if (c < 0x20) {
                this.writer.write('\\');
                this.writer.write('u');
                this.writer.write('0');
                this.writer.write('0');
                this.writer.write(HEX_DIGITS[(c >>> 4) & 0xF]);
                this.writer.write(HEX_DIGITS[c & 0xF]);
                count += 6;
              } else {
                this.writer.write(c);
                count += 1;
              }
              break;
          }
        }
      }

      this.writer.write('"');
      count += 1;

      this.bytesWritten += count;
    } catch (IOException ignored) {
    }
  }

  private void writeStringRaw(String str) {
    try {
      this.writer.write(str);
      this.bytesWritten += str.length(); // exact if ASCII, estimate otherwise
    } catch (IOException ignored) {
    }
  }
}
