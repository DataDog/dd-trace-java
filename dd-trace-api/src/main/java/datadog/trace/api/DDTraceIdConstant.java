package datadog.trace.api;

import datadog.trace.api.internal.util.LongStringUtils;

/**
 * Concrete {@link DDTraceId} backing the {@link DDTraceId#ZERO} and {@link DDTraceId#ONE}
 * constants.
 *
 * <p>It extends {@link DDTraceId} directly, so it is a sibling of {@link DD64bTraceId} rather than
 * a {@code DD64bTraceId}. That keeps {@code DDTraceId.<clinit>} free of any reference to the
 * subclass (see {@link DDTraceId#ZERO} for why that matters). It represents a 64-bit id and is
 * value-equal to the equivalent {@link DD64bTraceId}.
 *
 * <p>Invariant: this type must only ever be initialized as a consequence of {@code
 * DDTraceId.<clinit>} constructing the constants below. It is {@code final} and package-private,
 * and the only place it is instantiated is {@link DDTraceId}. {@code instanceof}/cast (used in
 * {@link #equals}) do not trigger class initialization, so they are safe. Do not add a static
 * member access or any other path that could initialize this class independently of {@code
 * DDTraceId}: that would let a thread hold this class's init lock while waiting for {@code
 * DDTraceId}, reintroducing the very class-initialization deadlock this design removes.
 */
final class DDTraceIdConstant extends DDTraceId {
  private final long id;
  private final String str;
  private String hexStr; // cache for hex string representation

  DDTraceIdConstant(long id, String str) {
    this.id = id;
    this.str = str;
  }

  @Override
  public String toString() {
    return this.str;
  }

  @Override
  public String toHexString() {
    String hexStr = this.hexStr;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (hexStr == null) {
      this.hexStr = hexStr = LongStringUtils.toHexStringPadded(this.id, 32);
    }
    return hexStr;
  }

  @Override
  public String toHexStringPadded(int size) {
    if (size > 16) {
      return toHexString();
    }
    return LongStringUtils.toHexStringPadded(this.id, size);
  }

  @Override
  public long toLong() {
    return this.id;
  }

  @Override
  public long toHighOrderLong() {
    return 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o instanceof DD64bTraceId) return this.id == ((DD64bTraceId) o).toLong();
    if (o instanceof DDTraceIdConstant) return this.id == ((DDTraceIdConstant) o).id;
    return false;
  }

  @Override
  public int hashCode() {
    return (int) (this.id ^ (this.id >>> 32));
  }
}
