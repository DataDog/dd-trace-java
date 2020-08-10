package datadog.trace.bootstrap.instrumentation.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Class that wraps a {@code String} and caches the UTF8 byte representation. Implements {@code
 * CharSequence} so that it can be mixed with normal{@code String} instances.
 *
 * <p>This class should be used judiciously for strings known not to vary much in the application's
 * lifecycle, such as constants and effective constant (e.g. resource names)
 */
public final class UTF8BytesString implements CharSequence {
  public static UTF8BytesString create(String string) {
    if (string == null) {
      return null;
    } else {
      // To make sure that we don't get an infinite circle in weak caches that are indexed on this
      // very String, we create a new wrapper String that we hold on to instead.
      return new UTF8BytesString(string);
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

  private static final Allocator ALLOCATOR = new Allocator();

  private final String string;
  private byte[] utf8Bytes = null;
  private int offset;
  private int length;

  private UTF8BytesString(String string) {
    this.string = string;
    ALLOCATOR.allocate(string, this);
  }

  /** Writes the UTF8 encoding of the wrapped {@code String}. */
  public void transferTo(ByteBuffer buffer) {
    buffer.put(utf8Bytes, offset, length);
  }

  public int encodedLength() {
    return length;
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

  private static class Allocator {

    private static final int PAGE_SIZE = 16384;

    private final List<byte[]> pages;
    private int currentPage = -1;
    int currentPosition = 0;

    Allocator() {
      this.pages = new ArrayList<>();
    }

    synchronized void allocate(String s, UTF8BytesString target) {
      byte[] utf8 = s.getBytes(UTF_8);
      byte[] page = getPageWithCapacity(utf8.length);
      System.arraycopy(utf8, 0, page, currentPosition, utf8.length);
      target.utf8Bytes = page;
      target.offset = currentPosition;
      target.length = utf8.length;
      currentPosition += utf8.length;
    }

    private byte[] getPageWithCapacity(int length) {
      if (length >= PAGE_SIZE) {
        throw new IllegalArgumentException("String too long: " + length);
      }
      if (currentPage < 0) {
        newPage();
      } else if (currentPosition + length >= PAGE_SIZE) {
        // might leave a lot of space empty at the end of the page, but
        // this is designed for small strings
        newPage();
      }
      return pages.get(currentPage);
    }

    private void newPage() {
      this.pages.add(new byte[PAGE_SIZE]);
      ++currentPage;
      currentPosition = 0;
    }
  }
}
