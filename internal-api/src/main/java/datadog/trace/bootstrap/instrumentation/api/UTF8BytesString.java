package datadog.trace.bootstrap.instrumentation.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;

/**
 * Class that wraps a {@code String} and caches the UTF8 byte representation. Implements {@code
 * CharSequence} so that it can be mixed with normal{@code String} instances.
 */
public final class UTF8BytesString implements CharSequence {

  public static final UTF8BytesString EMPTY = UTF8BytesString.create("");

  public static UTF8BytesString create(CharSequence sequence) {
    if (null == sequence) {
      return null;
    } else if (sequence instanceof UTF8BytesString) {
      return (UTF8BytesString) sequence;
    } else {
      return new UTF8BytesString(String.valueOf(sequence));
    }
  }

  public static UTF8BytesString create(byte[] utf8Bytes) {
    if (null == utf8Bytes) {
      return null;
    } else {
      return new UTF8BytesString(utf8Bytes);
    }
  }

  public static UTF8BytesString create(String string, byte[] utf8Bytes) {
    if (null == utf8Bytes) {
      return null;
    } else {
      return new UTF8BytesString(string, utf8Bytes);
    }
  }

  /**
   * Creates a new {@linkplain UTF8BytesString} instance using the provided {@linkplain ByteBuffer}
   * <br>
   * All available data from the current position are read and used as the backing array.
   *
   * @param utf8BytesBuffer the byte buffer containing UTF8 data
   * @return a new {@linkplain UTF8BytesString} instance or {@literal null}
   */
  public static UTF8BytesString create(ByteBuffer utf8BytesBuffer) {
    if (null == utf8BytesBuffer) {
      return null;
    } else {
      byte[] utf8Bytes = new byte[utf8BytesBuffer.remaining()];
      utf8BytesBuffer.get(utf8Bytes);
      return new UTF8BytesString(utf8Bytes);
    }
  }

  private final String string;
  private byte[] utf8Bytes;
  // Lazy hashCode cache. {@link String} already caches its own {@code hashCode} internally, but
  // the inter-class call through {@code string.hashCode()} still costs a virtual dispatch + the
  // String's own cached-hash field read + branch -- caching here saves all of that on subsequent
  // calls. Benign race in the same shape as {@link #utf8Bytes}: two threads computing the same
  // hash in parallel both produce the same value, and {@code int} writes are atomic per JLS so a
  // reader cannot observe a partial value.
  private int cachedHashCode;

  private UTF8BytesString(String string) {
    this.string = string;
  }

  private UTF8BytesString(byte[] utf8Bytes) {
    this(new String(utf8Bytes, UTF_8), utf8Bytes);
  }

  private UTF8BytesString(String string, byte[] utf8Bytes) {
    this.string = string;
    this.utf8Bytes = utf8Bytes;
  }

  /** Writes the UTF8 encoding of the wrapped {@code String}. */
  public void transferTo(ByteBuffer buffer) {
    encodeIfNecessary();
    buffer.put(utf8Bytes);
  }

  /** Writes the UTF8 encoding of the wrapped {@code String}. */
  public byte[] getUtf8Bytes() {
    encodeIfNecessary();
    return utf8Bytes;
  }

  public int encodedLength() {
    encodeIfNecessary();
    return utf8Bytes.length;
  }

  @Override
  public String toString() {
    return string;
  }

  private void encodeIfNecessary() {
    // benign and intentional race condition
    if (null == utf8Bytes) {
      utf8Bytes = string.getBytes(UTF_8);
    }
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
    int h = this.cachedHashCode;
    if (h == 0) {
      h = this.string.hashCode();
      this.cachedHashCode = h;
    }
    return h;
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
