package datadog.trace.api.internal.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class HexStringUtilsTest {

  @TableTest({
    "scenario      | highOrderBits       | lowOrderBits        | size",
    "zero-size-10  | 0                   | 0                   | 10  ",
    "zero-size-16  | 0                   | 0                   | 16  ",
    "zero-size-20  | 0                   | 0                   | 20  ",
    "zero-size-32  | 0                   | 0                   | 32  ",
    "zero-size-40  | 0                   | 0                   | 40  ",
    "one-two-10    | 1                   | 2                   | 10  ",
    "one-two-16    | 1                   | 2                   | 16  ",
    "one-two-20    | 1                   | 2                   | 20  ",
    "one-two-32    | 1                   | 2                   | 32  ",
    "one-two-40    | 1                   | 2                   | 40  ",
    "large-size-10 | 6536977903480360123 | 3270264562721133536 | 10  ",
    "large-size-16 | 6536977903480360123 | 3270264562721133536 | 16  ",
    "large-size-20 | 6536977903480360123 | 3270264562721133536 | 20  ",
    "large-size-32 | 6536977903480360123 | 3270264562721133536 | 32  ",
    "large-size-40 | 6536977903480360123 | 3270264562721133536 | 40  "
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
