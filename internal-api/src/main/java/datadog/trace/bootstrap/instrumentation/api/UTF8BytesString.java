package datadog.trace.bootstrap.instrumentation.api;

import java.nio.charset.StandardCharsets;

/**
 * Class that wraps a {@code String} and caches the UTF8 byte representation. Implements {@code
 * CharSequence} so that it can be mixed with normal{@code String} instances.
 */
public final class UTF8BytesString implements CharSequence {
  public static UTF8BytesString create(String string) {
    if (string == null) {
      return null;
    } else {
      // To make sure that we don't get an infinite circle in weak caches that are indexed on this
      // very String, we create a new wrapper String that we hold on to instead.
      return new UTF8BytesString(new String(string));
    }
  }

  public static UTF8BytesString create(CharSequence chars) {
    if (chars == null) {
      return null;
    } else if (chars instanceof UTF8BytesString) {
      return (UTF8BytesString) chars;
    } else if (chars instanceof String) {
      return create((String) chars);
    } else {
      return new UTF8BytesString(String.valueOf(chars));
    }
  }

  private final String string;
  private byte[] utf8Bytes = null;

  private UTF8BytesString(String string) {
    this.string = string;
  }

  /**
   * Returns a <code>byte[]</code> representing the UTF8 encoding of the wrapped {@code String}.
   * Please note that the same <code>byte[]</code> will be returned on each call, and the caller is
   * bound by honor, and the fear of purgatory, to not modify the <code>byte[]</code>.
   *
   * @return the byte array of the UTF8 encode string
   */
  public byte[] getUtf8Bytes() {
    byte[] bytes = this.utf8Bytes;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (bytes == null) {
      this.utf8Bytes = bytes = this.string.getBytes(StandardCharsets.UTF_8);
    }
    return bytes;
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
