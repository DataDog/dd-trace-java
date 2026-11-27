package datadog.trace.util;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.trace.api.function.NoEscape;

/**
 * A {@code CharSequence} that is a view of ASCII bytes in an array, without copying or decoding
 * them: the byte counterpart of {@link SubSequence}. Lets a value that arrived as bytes (a fetched
 * Kafka header, for instance) be parsed in place, for example with {@code LongStringUtils}' range
 * parsers, instead of being decoded into a {@code String} first.
 *
 * <p>{@link NoEscape}: a view shares its array, and for a fetched record that array is the whole
 * fetch buffer, so keeping a view anywhere longer-lived than the call that received it (a field, a
 * span, a context, a cache) pins all of it. A value that must be kept is copied with {@link
 * #toString()}.
 *
 * <p>Only ASCII bytes can be viewed as chars one-for-one, so {@link #ofAscii} returns null when a
 * byte isn't, and the caller decodes those bytes as before. Equality is identity, as for any {@code
 * CharSequence} that doesn't promise otherwise; compare contents with {@code toString()} or a
 * content comparison.
 */
@NoEscape
public final class AsciiSequence implements CharSequence {
  private final byte[] bytes;
  private final int offset;
  private final int length;

  private AsciiSequence(byte[] bytes, int offset, int length) {
    this.bytes = bytes;
    this.offset = offset;
    this.length = length;
  }

  /**
   * A view of {@code bytes[offset, offset + length)} if every one of those bytes is ASCII, else
   * null.
   */
  public static AsciiSequence ofAscii(byte[] bytes, int offset, int length) {
    if (offset < 0 || length < 0 || offset > bytes.length - length) {
      throw new IndexOutOfBoundsException(
          "offset " + offset + ", length " + length + ", array length " + bytes.length);
    }
    for (int i = offset, end = offset + length; i < end; i++) {
      if (bytes[i] < 0) {
        return null; // not ASCII
      }
    }
    return new AsciiSequence(bytes, offset, length);
  }

  @Override
  public int length() {
    return length;
  }

  @Override
  public char charAt(int index) {
    if (index < 0 || index >= length) {
      throw new IndexOutOfBoundsException("index " + index + ", length " + length);
    }
    return (char) bytes[offset + index];
  }

  /** A view of chars {@code [start, end)} of this one, sharing its array. */
  @Override
  public AsciiSequence subSequence(int start, int end) {
    if (start < 0 || end > length || start > end) {
      throw new IndexOutOfBoundsException("start " + start + ", end " + end + ", length " + length);
    }
    return new AsciiSequence(bytes, offset + start, end - start);
  }

  /** A copy, as a {@code String}: what to keep when the value must outlive the call. */
  @Override
  public String toString() {
    // the bytes are ASCII, so ISO-8859-1 decodes them one-for-one in a single copy
    return new String(bytes, offset, length, ISO_8859_1);
  }
}
