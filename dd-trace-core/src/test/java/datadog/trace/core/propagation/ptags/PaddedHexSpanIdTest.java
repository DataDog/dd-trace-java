package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.DDSpanId;
import org.tabletest.junit.TableTest;

class PaddedHexSpanIdTest {
  @TableTest({
    "scenario     | id                  ",
    "zero         | 0                   ",
    "one          | 1                   ",
    "typical      | 67667974448284343   ",
    "max signed   | 9223372036854775807 ",
    "all bits set | -1                  ",
    "min signed   | -9223372036854775808"
  })
  void spellsTheIdAsDDSpanIdDoes(long id) {
    String expected = DDSpanId.toHexStringPadded(id);
    PaddedHexSpanId hex = new PaddedHexSpanId(id);

    assertEquals(expected, hex.toString());
    assertEquals(16, hex.length());
    StringBuilder chars = new StringBuilder();
    for (int i = 0; i < hex.length(); i++) {
      chars.append(hex.charAt(i));
    }
    assertEquals(expected, chars.toString());
    StringBuilder appended = new StringBuilder("p:");
    hex.appendTo(appended);
    assertEquals("p:" + expected, appended.toString());
    assertEquals(expected.substring(4, 9), hex.subSequence(4, 9).toString());
    assertThrows(IndexOutOfBoundsException.class, () -> hex.charAt(16));
  }
}
