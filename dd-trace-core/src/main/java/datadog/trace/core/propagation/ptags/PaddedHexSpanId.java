package datadog.trace.core.propagation.ptags;

import datadog.trace.api.DDSpanId;

/**
 * A span id as the 16 lower-case hex digits of a W3C {@code tracestate}'s {@code p} element,
 * without formatting it into a String. Injecting a span's {@code tracestate} writes its id once;
 * formatting it first cost a byte array and a String per injection. {@link W3CPTagsCodec} appends
 * the digits straight into the header it builds.
 */
public final class PaddedHexSpanId implements CharSequence {
  private static final char[] DIGITS = "0123456789abcdef".toCharArray();
  private static final int LENGTH = 16;

  private final long id;

  public PaddedHexSpanId(long id) {
    this.id = id;
  }

  @Override
  public int length() {
    return LENGTH;
  }

  @Override
  public char charAt(int index) {
    if (index < 0 || index >= LENGTH) {
      throw new IndexOutOfBoundsException("index " + index + ", length " + LENGTH);
    }
    return DIGITS[(int) (id >>> (60 - 4 * index)) & 0xF];
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return toString().subSequence(start, end);
  }

  /** Appends the 16 digits to {@code sb}. */
  void appendTo(StringBuilder sb) {
    for (int shift = 60; shift >= 0; shift -= 4) {
      sb.append(DIGITS[(int) (id >>> shift) & 0xF]);
    }
  }

  @Override
  public String toString() {
    return DDSpanId.toHexStringPadded(id);
  }
}
