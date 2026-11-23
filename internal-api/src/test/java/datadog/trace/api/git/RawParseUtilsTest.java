package datadog.trace.api.git;

import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class RawParseUtilsTest {

  @Test
  void testDecodeNoFallback() {
    String str = new String("some-string".getBytes(UTF_8), UTF_16);
    byte[] encoded = str.getBytes(UTF_8);

    String decoded = RawParseUtils.decode(encoded, 0, encoded.length);

    assertEquals(str, decoded);
  }

  @Test
  void testExtractBinaryString() {
    String str = new String("some-string".getBytes(UTF_8), UTF_8);
    byte[] encoded = str.getBytes(UTF_8);

    String decoded = RawParseUtils.extractBinaryString(encoded, 0, encoded.length);

    assertEquals(str, decoded);
  }

  @TableTest({
    "scenario   | input              | expectedLong  ",
    "no sign    | '  1000000000000'  | 1000000000000 ",
    "plus sign  | '  +1000000000000' | 1000000000000 ",
    "minus sign | '  -1000000000000' | -1000000000000"
  })
  void testParseLongBase10(String input, long expectedLong) {
    assertEquals(expectedLong, RawParseUtils.parseLongBase10(input.getBytes(UTF_8), 0));
  }

  @TableTest({
    "scenario   | input   | expectedInt",
    "no sign    | '  10'  | 10         ",
    "plus sign  | '  +10' | 10         ",
    "minus sign | '  -10' | -10        "
  })
  void testParseIntBase10(String input, int expectedInt) {
    assertEquals(expectedInt, RawParseUtils.parseBase10(input.getBytes(UTF_8), 0));
  }
}
