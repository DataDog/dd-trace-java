package datadog.context.propagation;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class AsciiValueBuilderTest {

  @TableTest({
    "Scenario   | value                | digits | expected        ",
    "zero       | 0                    | 4      | 0000            ",
    "padded     | 255                  | 4      | 00ff            ",
    "full width | -1                   | 16     | ffffffffffffffff",
    "high bit   | -9223372036854775808 | 16     | 8000000000000000",
    "truncated  | 4660                 | 2      | 34              "
  })
  void appendsHexDigits(long value, int digits, String expected) {
    assertEquals(expected, new AsciiValueBuilder(digits).appendHex(value, digits).toString());
  }

  @Test
  void appendsDecimalDigitsAfterOtherText() {
    assertEquals(
        "t0=-42/18446744073709551615",
        new AsciiValueBuilder(8)
            .append("t0=")
            .appendDecimal(-42)
            .append('/')
            .appendUnsignedDecimal(-1)
            .toString());
  }

  @Test
  void returnsItsOwnArrayWhenExactlyFull() {
    AsciiValueBuilder builder = new AsciiValueBuilder(5).append("ab").append('-').append("cd");

    byte[] bytes = builder.toBytes();

    assertArrayEquals("ab-cd".getBytes(US_ASCII), bytes);
    assertSame(bytes, builder.toBytes());
  }

  @Test
  void copiesOutWhenNotFull() {
    AsciiValueBuilder builder = new AsciiValueBuilder(8).append("abc");

    assertArrayEquals("abc".getBytes(US_ASCII), builder.toBytes());
  }

  @Test
  void growsPastItsCapacity() {
    AsciiValueBuilder builder = new AsciiValueBuilder(2).append("abc").appendHex(0xab, 2);

    assertEquals("abcab", builder.toString());
    assertArrayEquals("abcab".getBytes(US_ASCII), builder.toBytes());
  }

  @Test
  void isACharSequenceOverItsBytes() {
    AsciiValueBuilder builder = new AsciiValueBuilder(4).append("00-f");

    assertEquals(4, builder.length());
    assertEquals('f', builder.charAt(3));
    assertEquals("0-", builder.subSequence(1, 3).toString());
    assertThrows(IndexOutOfBoundsException.class, () -> builder.charAt(4));
  }

  @Test
  void rejectsNonAsciiChars() {
    assertThrows(IllegalArgumentException.class, () -> new AsciiValueBuilder(1).append('é'));
    assertThrows(IllegalArgumentException.class, () -> new AsciiValueBuilder(4).append("café"));
  }

  @Test
  void setsAStringByDefault() {
    StringBuilder set = new StringBuilder();
    CarrierSetter<StringBuilder> setter =
        (carrier, key, value) -> carrier.append(key).append('=').append(value);

    setter.setAscii(set, "traceparent", new AsciiValueBuilder(4).append("00-f"));

    assertEquals("traceparent=00-f", set.toString());
  }
}
