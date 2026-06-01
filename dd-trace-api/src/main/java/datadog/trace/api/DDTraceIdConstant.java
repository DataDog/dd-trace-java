package datadog.trace.api;

import datadog.trace.api.internal.util.LongStringUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Backs {@link DDTraceId#ZERO} and {@link DDTraceId#ONE}. A 64-bit id that is a sibling of {@link
 * DD64bTraceId} (it extends {@link DDTraceId} directly) so initializing {@code DDTraceId} never
 * initializes its subclass; value-equal to the equivalent {@link DD64bTraceId}.
 *
 * <p>Only ever initialize this through {@link DDTraceId}'s constants. Initializing it independently
 * (e.g. a static-member access) would bring back the class-initialization deadlock.
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
  @SuppressFBWarnings(
      value = "EQ_CHECK_FOR_OPERAND_NOT_COMPATIBLE_WITH_THIS",
      justification = "DD64bTraceId is a sibling type; ZERO/ONE are equal to it by 64-bit value.")
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
