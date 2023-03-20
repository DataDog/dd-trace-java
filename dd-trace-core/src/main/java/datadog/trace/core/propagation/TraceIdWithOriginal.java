package datadog.trace.core.propagation;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDTraceId;

/** A {@link DDTraceId} along with its original {@link String} representation. */
public abstract class TraceIdWithOriginal implements DDTraceId {
  /** The original {@link String} representation. */
  protected final String original;

  protected final DDTraceId delegate;

  protected TraceIdWithOriginal(String original, DDTraceId delegate) {
    this.original = original;
    this.delegate = delegate;
  }

  // TODO Javadoc
  public boolean isValid() {
    // TODO Check 128-bit trace id activation/logging?
    return this.delegate.toLong() != 0;
    // return !DD128bTraceId.ZERO.equals(this.delegate);
  }

  @Override
  public String toHexString() {
    return this.delegate.toHexString();
  }

  @Override
  public String toHexStringPadded(int size) {
    return this.delegate.toHexStringPadded(size);
  }

  @Override
  @Deprecated
  public long toLong() {
    return this.delegate.toLong();
  }

  @Override
  public String toString() {
    return this.delegate.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TraceIdWithOriginal that = (TraceIdWithOriginal) o;
    return delegate.equals(that.delegate);
  }

  @Override
  public int hashCode() {
    return this.delegate.hashCode();
  }

  /** A B3 formatted {@link DDTraceId}. */
  public static final class B3TraceId extends TraceIdWithOriginal {
    private B3TraceId(String original, DDTraceId delegate) {
      super(original, delegate);
    }

    /**
     * Create a {@link B3TraceId} from a B3 TraceId string.
     *
     * @param s The B3 TraceId string.
     */
    public static B3TraceId fromHex(String s) {
      return new B3TraceId(s, DD128bTraceId.fromHex(s));
    }

    /**
     * Gte the original B3 TraceId.
     *
     * @return The original B3 TraceId.
     */
    public String getB3Original() {
      return this.original;
    }
  }

  /** A W3C formatted {@link DDTraceId}. */
  public static final class W3CTraceId extends TraceIdWithOriginal {
    private W3CTraceId(String original, DDTraceId delegate) {
      super(original, delegate);
    }

    /**
     * Create a {@link W3CTraceId} from a string containing a W3C trace-id.
     *
     * @param s The string containing a W3C trace-id.
     * @param start The start index of the W3C trace-id.
     */
    public static W3CTraceId fromHex(String s, int start) {
      String original;
      if (start == 0 && s.length() == 32) {
        original = s;
      } else if (start + 32 > s.length()) {
        throw new IllegalArgumentException("Invalid start or length");
      } else {
        original = s.substring(start, start + 32);
      }
      return new W3CTraceId(original, DD128bTraceId.fromHex(original));
    }

    /**
     * Get the original W3C TraceId.
     *
     * @return The original WPC TraceId.
     */
    public String getW3COriginal() {
      return this.original;
    }
  }
}
