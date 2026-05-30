package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.propagation.B3TraceId;
import org.junit.jupiter.api.Test;

/**
 * {@link DDTraceId#isZero()} across every {@link DDTraceId} subtype, including the {@code ZERO}/
 * {@code ONE} sibling constants and {@link B3TraceId} (defined in dd-trace-core).
 *
 * <p>{@code isZero()} is the value-based replacement for the old {@code == DDTraceId.ZERO} identity
 * checks, so it must recognize a zero id regardless of concrete type or how it was created (a zero
 * parsed via the 64-bit factories is a distinct instance from the {@code ZERO} singleton).
 */
class TraceIdIsZeroTest {

  @Test
  void zeroValuedIdsOfEveryTypeAreZero() {
    assertTrue(DDTraceId.ZERO.isZero());
    assertTrue(DDTraceId.from(0).isZero());
    assertTrue(DDTraceId.from("0").isZero());
    assertTrue(DDTraceId.fromHex("0").isZero());
    assertTrue(DD64bTraceId.from(0).isZero());
    assertTrue(DD128bTraceId.from(0, 0).isZero());
    assertTrue(B3TraceId.fromHex("0").isZero());
  }

  @Test
  void nonZeroIdsAreNotZero() {
    assertFalse(DDTraceId.ONE.isZero());
    assertFalse(DDTraceId.from(1).isZero());
    assertFalse(DD64bTraceId.from(42).isZero());
    assertFalse(DD128bTraceId.from(0, 1).isZero());
    // High-order bits set, low-order zero: still not a zero TraceId.
    assertFalse(DD128bTraceId.from(7, 0).isZero());
    assertFalse(B3TraceId.fromHex("2a").isZero());
  }
}
