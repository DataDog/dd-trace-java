package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Behavior of the {@link DDTraceId#ZERO}/{@link DDTraceId#ONE} sibling constants. */
class DDTraceIdConstantsTest {

  @Test
  void zeroAndOneFormatLikeTheEquivalentDD64bTraceId() {
    assertEquals("0", DDTraceId.ZERO.toString());
    assertEquals("00000000000000000000000000000000", DDTraceId.ZERO.toHexString());
    assertEquals("0000000000000000", DDTraceId.ZERO.toHexStringPadded(16));
    assertEquals("00000000000000000000000000000000", DDTraceId.ZERO.toHexStringPadded(32));
    assertEquals(0L, DDTraceId.ZERO.toLong());
    assertEquals(0L, DDTraceId.ZERO.toHighOrderLong());

    assertEquals("1", DDTraceId.ONE.toString());
    assertEquals("00000000000000000000000000000001", DDTraceId.ONE.toHexString());
    assertEquals("0000000000000001", DDTraceId.ONE.toHexStringPadded(16));
    assertEquals(1L, DDTraceId.ONE.toLong());
    assertEquals(0L, DDTraceId.ONE.toHighOrderLong());
  }

  @Test
  void isZeroReflectsTheValue() {
    assertTrue(DDTraceId.ZERO.isZero());
    assertTrue(DDTraceId.from(0).isZero());
    assertTrue(DDTraceId.from("0").isZero());
    assertTrue(DDTraceId.fromHex("0").isZero());
    assertTrue(DD64bTraceId.from(0).isZero());

    assertFalse(DDTraceId.ONE.isZero());
    assertFalse(DDTraceId.from(1).isZero());
    assertFalse(DD64bTraceId.from(42).isZero());
  }
}
