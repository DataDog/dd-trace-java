package datadog.trace.bootstrap.instrumentation.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;

/**
 * Class that wraps a {@code String} and caches the UTF8 byte representation. Implements {@code
 * CharSequence} so that it can be mixed with normal{@code String} instances.
 */
public final class UTF8BytesString implements CharSequence {

  @Deprecated
  public static UTF8BytesString createConstant(CharSequence string) {
    return create(string);
  }

  public static UTF8BytesString create(CharSequence sequence) {
    if (null == sequence) {
      return null;
    } else if (sequence instanceof UTF8BytesString) {
      return (UTF8BytesString) sequence;
    } else {
      return new UTF8BytesString(sequence);
    }
  }

  private final String string;
  private final byte[] utf8Bytes;

  private UTF8BytesString(CharSequence chars) {
    this.string = String.valueOf(chars);
    this.utf8Bytes = string.getBytes(UTF_8);
  }

  /** Writes the UTF8 encoding of the wrapped {@code String}. */
  public void transferTo(ByteBuffer buffer) {
    buffer.put(utf8Bytes);
  }

  public int encodedLength() {
    return utf8Bytes.length;
  }

  @Override
  public String toString() {
    return string;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null) return false;
    String that = null;
    if (o instanceof UTF8BytesString) {
      that = ((UTF8BytesString) o).string;
    }
    return this.string.equals(that);
  }

  @Override
  public int hashCode() {
    return this.string.hashCode();
  }

  @Override
  public int length() {
    return this.string.length();
  }

  @Override
  public char charAt(int index) {
    return this.string.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return this.string.subSequence(start, end);
  }
}
