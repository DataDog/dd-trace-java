package datadog.trace.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * Light weight JSON writer with no dependencies other than JDK.
 * Loosely modeled after GSON JsonWriter
 */
public final class JsonBuffer implements Flushable {
  private ByteArrayOutputStream bytesOut;
  private OutputStreamWriter writer;

  private byte[] cachedBytes = null;
  private boolean lastWasValue = false;

  public JsonBuffer() {
    this.reset();
  }

  public void reset() {
    bytesOut = new ByteArrayOutputStream();
    writer = new OutputStreamWriter(this.bytesOut, Charset.forName("utf-8"));

    cachedBytes = null;
    lastWasValue = false;
  }

  public JsonBuffer beginObject() {
    injectCommaIfNeeded();

    return write('{');
  }

  public JsonBuffer endObject() {
    return write('}');
  }

  public JsonBuffer object(JsonBuffer objectContents) {
    beginObject();
    writeBytesRaw(objectContents.toByteArray());
    endObject();

    return this;
  }

  public JsonBuffer name(String key) {
    injectCommaIfNeeded();

    return writeStringLiteral(key).write(':');
  }

  public JsonBuffer nullValue() {
    injectCommaIfNeeded();
    endsValue();

    return this.writeStringRaw("null");
  }

  public JsonBuffer value(JsonBuffer buffer) {
    injectCommaIfNeeded();
    endsValue();

    return this.writeBytesRaw(buffer.toByteArray());
  }

  public JsonBuffer value(boolean value) {
    injectCommaIfNeeded();
    endsValue();

    return writeStringRaw(value ? "true" : "false");
  }

  public JsonBuffer value(String value) {
    injectCommaIfNeeded();
    endsValue();

    return writeStringLiteral(value);
  }

  public JsonBuffer value(int value) {
    injectCommaIfNeeded();
    endsValue();

    return writeStringRaw(Integer.toString(value));
  }

  public JsonBuffer beginArray() {
    injectCommaIfNeeded();

    return write('[');
  }

  public JsonBuffer endArray() {
    endsValue();

    return write(']');
  }

  public JsonBuffer array(String element) {
    beginArray();
    value(element);
    endArray();

    return this;
  }

  public JsonBuffer array(String[] elements) {
    beginArray();
    for (String e : elements) {
      value(e);
    }
    endArray();

    return this;
  }

  public JsonBuffer array(JsonBuffer arrayContents) {
    beginArray();
    writeBytesRaw(arrayContents.toByteArray());
    endArray();

    return this;
  }

  public void flush() {
    try {
      writer.flush();
    } catch (IOException e) {
      // ignore
    }
  }

  public byte[] toByteArray() {
    byte[] cachedBytes = this.cachedBytes;
    if (cachedBytes != null) {
      return cachedBytes;
    }

    try {
      writer.flush();
    } catch (IOException e) {
      // ignore
    }

    cachedBytes = bytesOut.toByteArray();
    this.cachedBytes = cachedBytes;
    return cachedBytes;
  }

  void injectCommaIfNeeded() {
    if (lastWasValue) this.write(',');
    lastWasValue = false;
  }

  void endsValue() {
    lastWasValue = true;
  }

  void clearBytesCache() {
    cachedBytes = null;
  }

  private JsonBuffer write(char ch) {
    clearBytesCache();

    try {
      writer.write(ch);
    } catch (IOException e) {
      // ignore
    }
    return this;
  }

  private JsonBuffer writeStringLiteral(String str) {
    clearBytesCache();

    try {
      writer.write('"');

      // DQH - indexOf is usually intrinsifed to use SIMD &
      // no escaping will be the common case
      if (str.indexOf('"') == -1) {
        writer.write(str);
      } else {
        for (int i = 0; i < str.length(); ++i) {
          char ch = str.charAt(i);

          switch (ch) {
            case '"':
              writer.write("\\\"");
              break;

            default:
              writer.write(ch);
              break;
          }
        }
      }
      writer.write('"');
    } catch (IOException e) {
      // ignore
    }

    return this;
  }

  private JsonBuffer writeStringRaw(String str) {
    clearBytesCache();

    try {
      writer.write(str);
    } catch (IOException e) {
      // ignore
    }
    return this;
  }

  private JsonBuffer writeBytesRaw(byte[] bytes) {
    clearBytesCache();

    try {
      writer.flush();

      bytesOut.write(bytes);
    } catch (IOException e) {
      // ignore
    }
    return this;
  }
}
