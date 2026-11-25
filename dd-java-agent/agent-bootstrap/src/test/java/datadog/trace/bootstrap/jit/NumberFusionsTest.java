package datadog.trace.bootstrap.jit;

import static datadog.trace.bootstrap.jit.Outcomes.assertSameOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Differential tests: parsing a substring in place must behave exactly like the original. */
class NumberFusionsTest {

  private static final String[] NUMBERS = {
    "",
    "0",
    "-0",
    "+0",
    "7",
    "-7",
    "+7",
    "-",
    "+",
    "+-1",
    "--1",
    "12",
    "123456789",
    "999999999",
    "1000000000",
    "2147483647",
    "2147483648",
    "-2147483648",
    "-2147483649",
    "0000000001",
    "00000000000000000001",
    "9223372036854775807",
    "9223372036854775808",
    "-9223372036854775808",
    "-9223372036854775809",
    "999999999999999999",
    "1000000000000000000",
    "٣",
    "1٣",
    "３",
    "1_000",
    "1e3",
    " 1",
    "1 ",
    "0x1F",
    "𝟏",
    "12a"
  };

  @Test
  void matchesJdkForEveryRange() {
    for (String number : NUMBERS) {
      for (String padded : new String[] {number, "[" + number + "]", "ab" + number}) {
        checkRanges(padded);
      }
    }
    checkRanges(null);
  }

  @Test
  void matchesJdkOnRandomInputs() {
    Random random = new Random(11);
    String alphabet = "0123456789+-x ٣";
    for (int i = 0; i < 3000; i++) {
      StringBuilder s = new StringBuilder();
      int length = random.nextInt(22);
      for (int j = 0; j < length; j++) {
        s.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      checkRanges(s.toString());
    }
  }

  private static void checkRanges(String s) {
    int length = null == s ? 2 : s.length();
    List<int[]> ranges = new ArrayList<>();
    for (int begin = -1; begin <= length + 1; begin++) {
      for (int end = -1; end <= length + 1; end++) {
        ranges.add(new int[] {begin, end});
      }
    }
    for (int[] range : ranges) {
      int b = range[0];
      int e = range[1];
      assertSameOutcome(
          () -> Integer.parseInt(s.substring(b, e)),
          () -> NumberFusions.parseIntSubstring(s, b, e));
      assertSameOutcome(
          () -> Long.parseLong(s.substring(b, e)), () -> NumberFusions.parseLongSubstring(s, b, e));
      assertSameOutcome(
          () -> Integer.valueOf(s.substring(b, e)), () -> NumberFusions.valueOfSubstring(s, b, e));
    }
    for (int begin = -1; begin <= length + 1; begin++) {
      int b = begin;
      assertSameOutcome(
          () -> Integer.parseInt(s.substring(b)), () -> NumberFusions.parseIntSubstring(s, b));
      assertSameOutcome(
          () -> Long.parseLong(s.substring(b)), () -> NumberFusions.parseLongSubstring(s, b));
      assertSameOutcome(
          () -> Integer.valueOf(s.substring(b)), () -> NumberFusions.valueOfSubstring(s, b));
    }
  }
}
