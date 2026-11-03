package datadog.context.propagation;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.Arrays;

/**
 * Builds an ASCII carrier value as bytes. A {@link CarrierSetter} for a carrier that stores bytes
 * takes them as they are; others take its {@link #toString() String} form.
 *
 * <p>Values must be ASCII, as propagation header values are: each char is stored as one byte, and
 * appending a non-ASCII char throws {@link IllegalArgumentException}.
 *
 * <p>Size it with the value's length when that's known, so {@link #toBytes()} needs no copy.
 */
public final class AsciiValueBuilder implements CharSequence {
  private static final byte[] HEX_DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  private byte[] bytes;
  private int length;

  public AsciiValueBuilder(int capacity) {
    this.bytes = new byte[capacity];
  }

  public AsciiValueBuilder append(char c) {
    ensureCapacity(1);
    bytes[length++] = ascii(c);
    return this;
  }

  public AsciiValueBuilder append(CharSequence value) {
    int count = value.length();
    ensureCapacity(count);
    for (int i = 0; i < count; i++) {
      bytes[length++] = ascii(value.charAt(i));
    }
    return this;
  }

  /** Appends {@code value} as {@code digits} lowercase hex digits, zero-padded on the left. */
  public AsciiValueBuilder appendHex(long value, int digits) {
    ensureCapacity(digits);
    for (int i = length + digits - 1; i >= length; i--) {
      bytes[i] = HEX_DIGITS[(int) (value & 0xF)];
      value >>>= 4;
    }
    length += digits;
    return this;
  }

  /**
   * The value as bytes. When the builder is exactly full, this is its own array, without a copy, so
   * the builder must not be appended to after this.
   */
  public byte[] toBytes() {
    return length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
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
    return (char) bytes[index];
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return toString().subSequence(start, end);
  }

  @Override
  public String toString() {
    return new String(bytes, 0, length, ISO_8859_1);
  }

  private void ensureCapacity(int extra) {
    if (length + extra > bytes.length) {
      bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + extra));
    }
  }

  private static byte ascii(char c) {
    if (c >= 0x80) {
      throw new IllegalArgumentException("Not an ASCII char: " + (int) c);
    }
    return (byte) c;
  }
}
