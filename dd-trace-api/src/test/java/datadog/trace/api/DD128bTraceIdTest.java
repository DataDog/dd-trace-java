package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("128-bit trace identifiers")
class DD128bTraceIdTest {

  @Test
  @DisplayName("parse upper-case partial hex and cache the normalized hex string")
  void parsesUpperCasePartialHexAndNormalizesCachedHexString() {
    DD128bTraceId traceId =
        DD128bTraceId.fromHex("xx123456789ABCDEF0FEDCBA9876543210yy", 2, 32, false);

    assertEquals("123456789abcdef0fedcba9876543210", traceId.toHexString());
    assertEquals("fedcba9876543210", traceId.toHexStringPadded(16));
  }

  @Test
  @DisplayName("reject negative partial hex start")
  void rejectsNegativePartialHexStart() {
    assertThrows(NumberFormatException.class, () -> DD128bTraceId.fromHex("1234", -1, 1, false));
  }

  @Test
  @DisplayName("compare by high and low bits")
  void comparesByHighAndLowBits() {
    DD128bTraceId traceId = DD128bTraceId.from(1, 2);
    DD128bTraceId sameTraceId = DD128bTraceId.from(1, 2);

    assertEquals(traceId, traceId);
    assertEquals(traceId, sameTraceId);
    assertEquals(traceId.hashCode(), sameTraceId.hashCode());
    assertNotEquals(traceId, DD128bTraceId.from(3, 2));
    assertNotEquals(traceId, DD128bTraceId.from(1, 4));
    assertNotEquals(traceId, "1");
  }

  @Test
  @DisplayName("reuse the computed decimal string")
  void reusesComputedDecimalString() {
    DD128bTraceId traceId = DD128bTraceId.from(0, 42);

    String firstString = traceId.toString();

    assertEquals("42", firstString);
    assertSame(firstString, traceId.toString());
  }
}
