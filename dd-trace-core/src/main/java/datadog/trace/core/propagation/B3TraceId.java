package datadog.trace.core.propagation;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDTraceId;

/** A B3 {@link DDTraceId} along with its original {@link String} representation. */
public class B3TraceId extends DDTraceId {
  /** The original {@link String} representation. */
  protected final String original;

  protected final DDTraceId delegate;

  /**
   * Create a {@link B3TraceId} from a B3 TraceId string.
   *
   * @param s The B3 TraceId string.
   */
  public static B3TraceId fromHex(String s) {
    return new B3TraceId(s, DD128bTraceId.fromHex(s));
  }

  protected B3TraceId(String original, DDTraceId delegate) {
    this.original = original;
    this.delegate = delegate;
  }

  /**
   * Gets the original B3 TraceId.
   *
   * @return The original B3 TraceId.
   */
  public String getOriginal() {
    return this.original;
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
  public long toLong() {
    return this.delegate.toLong();
  }

  @Override
  public long toHighOrderLong() {
    return this.delegate.toHighOrderLong();
  }

  @Override
  public String toString() {
    return this.delegate.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    B3TraceId that = (B3TraceId) o;
    return delegate.equals(that.delegate);
  }

  @Override
  public int hashCode() {
    return this.delegate.hashCode();
  }
}
