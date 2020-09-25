package datadog.trace.bootstrap.instrumentation.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Class that wraps a {@code String} and caches the UTF8 byte representation. Implements {@code
 * CharSequence} so that it can be mixed with normal{@code String} instances.
 */
public final class UTF8BytesString implements CharSequence {

  /*
   * This method should be used judiciously for strings known not to vary much in the application's
   * lifecycle, such as constants and effective constant (e.g. resource names)
   */
  public static UTF8BytesString createConstant(CharSequence string) {
    return create(string, true);
  }

  public static UTF8BytesString create(CharSequence chars) {
    return create(chars, false);
  }

  public static UTF8BytesString createWeak(String chars) {
    return new UTF8BytesString(chars, false, true);
  }

  private static UTF8BytesString create(CharSequence sequence, boolean constant) {
    if (null == sequence) {
      return null;
    } else if (sequence instanceof UTF8BytesString) {
      return (UTF8BytesString) sequence;
    } else {
      return new UTF8BytesString(sequence, constant);
    }
  }

  private static final Allocator ALLOCATOR = new Allocator();

  private final String string;
  private final byte[] utf8Bytes;
  private final int offset;
  private final int length;

  private UTF8BytesString(CharSequence chars, boolean constant) {
    this(chars, constant, false);
  }

  private UTF8BytesString(CharSequence chars, boolean constant, boolean weak) {
    if (weak) {
      // To make sure that we don't get an infinite circle in weak caches that are indexed on this
      // very String, we create a new wrapper String that we hold on to instead.
      this.string = chars instanceof String ? new String((String) chars) : String.valueOf(chars);
    } else {
      this.string = String.valueOf(chars);
    }

    byte[] utf8Bytes = string.getBytes(UTF_8);
    this.length = utf8Bytes.length;
    if (constant) {
      Allocator.Allocation allocation = ALLOCATOR.allocate(utf8Bytes);
      if (null != allocation) {
        this.offset = allocation.position;
        this.utf8Bytes = allocation.page;
        return;
      }
    }
    this.offset = 0;
    this.utf8Bytes = utf8Bytes;
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

    private static final class Allocation {
      final int position;
      final byte[] page;

      private Allocation(int position, byte[] page) {
        this.position = position;
        this.page = page;
      }
    }

    private static final int PAGE_SIZE = 8192;

    private final List<byte[]> pages;
    private int currentPage = -1;
    int currentPosition = 0;

    Allocator() {
      this.pages = new ArrayList<>();
    }

    synchronized Allocation allocate(byte[] utf8) {
      byte[] page = getPageWithCapacity(utf8.length);
      if (null == page) { // too big
        return null;
      }
      System.arraycopy(utf8, 0, page, currentPosition, utf8.length);
      currentPosition += utf8.length;
      return new Allocation(currentPosition - utf8.length, page);
    }

    private byte[] getPageWithCapacity(int length) {
      if (length >= PAGE_SIZE) {
        return null;
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
