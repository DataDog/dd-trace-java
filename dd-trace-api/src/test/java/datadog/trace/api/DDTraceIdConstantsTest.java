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
    assertEquals("00000000000000000000000000000001", DDTraceId.ONE.toHexStringPadded(32));
    assertEquals(1L, DDTraceId.ONE.toLong());
    assertEquals(0L, DDTraceId.ONE.toHighOrderLong());
  }

  @Test
  void isValidReflectsTheValue() {
    assertFalse(DDTraceId.ZERO.isValid());
    assertFalse(DDTraceId.from(0).isValid());
    assertFalse(DDTraceId.from("0").isValid());
    assertFalse(DDTraceId.fromHex("0").isValid());
    assertFalse(DD64bTraceId.from(0).isValid());

    assertTrue(DDTraceId.ONE.isValid());
    assertTrue(DDTraceId.from(1).isValid());
    assertTrue(DD64bTraceId.from(42).isValid());
  }

  @Test
  void constantsAreValueEqualToTheEquivalentDD64bTraceId() {
    // ZERO/ONE used to be DD64bTraceId instances; they are now a sibling type but must stay
    // value-equal (both directions, with matching hashCode) to the equivalent DD64bTraceId.
    assertEquals(DDTraceId.ZERO, DD64bTraceId.from(0));
    assertEquals(DD64bTraceId.from(0), DDTraceId.ZERO);
    assertEquals(DDTraceId.ZERO.hashCode(), DD64bTraceId.from(0).hashCode());

    assertEquals(DDTraceId.ONE, DD64bTraceId.from(1));
    assertEquals(DD64bTraceId.from(1), DDTraceId.ONE);
    assertEquals(DDTraceId.ONE.hashCode(), DD64bTraceId.from(1).hashCode());
  }
}
