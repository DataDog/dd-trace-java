package datadog.trace.api.internal.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HexStringUtilsTest {

  @ParameterizedTest(name = "test hexadecimal String representations high={0} low={1} size={2}")
  @CsvSource(
      value = {
        "0|0|10",
        "0|0|16",
        "0|0|20",
        "0|0|32",
        "0|0|40",
        "1|2|10",
        "1|2|16",
        "1|2|20",
        "1|2|32",
        "1|2|40",
        "6536977903480360123|3270264562721133536|10",
        "6536977903480360123|3270264562721133536|16",
        "6536977903480360123|3270264562721133536|20",
        "6536977903480360123|3270264562721133536|32",
        "6536977903480360123|3270264562721133536|40"
      },
      delimiter = '|')
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
