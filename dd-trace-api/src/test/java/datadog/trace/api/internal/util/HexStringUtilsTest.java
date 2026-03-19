package datadog.trace.api.internal.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class HexStringUtilsTest {

  @TableTest({
    "scenario      | highOrderBits       | lowOrderBits        | size",
    "zero          | 0                   | 0                   | {10, 16, 20, 32, 40}",
    "one-two       | 1                   | 2                   | {10, 16, 20, 32, 40}",
    "large         | 6536977903480360123 | 3270264562721133536 | {10, 16, 20, 32, 40}"
  })
  @ParameterizedTest(name = "test hexadecimal String representations high={0} low={1} size={2}")
  void testHexadecimalStringRepresentations(long highOrderBits, long lowOrderBits, int size) {
    int highOrderSize = Math.min(16, Math.max(0, size - 16));
    int lowOrderSize = Math.min(16, size);

    String highOrder =
        highOrderSize == 0 ? "" : LongStringUtils.toHexStringPadded(highOrderBits, highOrderSize);
    String lowOrder = LongStringUtils.toHexStringPadded(lowOrderBits, lowOrderSize);
    String lowOrderOnly = LongStringUtils.toHexStringPadded(lowOrderBits, size);

    assertEquals(
        highOrder + lowOrder, LongStringUtils.toHexStringPadded(highOrderBits, lowOrderBits, size));
    assertEquals(lowOrderOnly, LongStringUtils.toHexStringPadded(0L, lowOrderBits, size));
  }
}
