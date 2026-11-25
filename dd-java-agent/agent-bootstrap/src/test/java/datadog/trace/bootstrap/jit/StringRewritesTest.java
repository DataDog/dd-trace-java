package datadog.trace.bootstrap.jit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Differential tests: every helper must behave exactly like the JDK method it replaces. */
class StringRewritesTest {

  private static final String[] REGEXES = {
    "\\s+",
    " +",
    "\\s*,\\s*",
    "\\s*;\\s*",
    "\\s*:\\s*",
    "\\s*=\\s*",
    ",\\s*",
    ";\\s*",
    ":\\s*",
    "::",
    "a b",
    "\\.",
    "\\.\\.",
    "ab",
    "\\|\\|",
    "\\\\",
    ",",
    ".",
    "|",
    "\\s",
    "a|b",
    "(a)",
    "[",
    "\\",
    "",
    "\\d",
    "\\Qa\\E",
    "\\1",
    "b*",
    "a{2}",
    "\\u0041",
  };

  private static final String[] REPLACEMENTS = {"x", "", "$0", "\\$", "y$0z", "\\\\", "$"};

  private static final int[] LIMITS = {-1, 0, 1, 2, 3, 5};

  private static final String[] FIXED_INPUTS = {
    "",
    " ",
    "  ",
    "a",
    "a b",
    " a  b ",
    "a,b",
    "a , b ,c",
    ",a,,b,,",
    " , ",
    "a::b::",
    "::a",
    "a.b.c",
    "a..b",
    "x\t\ny\u000Bz\f\r",
    "a=b; c = d ;",
    "a ; b;;",
    "ab ab",
    "aab",
    "a|b||c",
    "a\\b",
    " a b",
    "k: v, k2 :v2",
  };

  @BeforeEach
  void clearCache() {
    PatternCache.clear();
  }

  @Test
  void matchesJdkOnFixedInputs() {
    for (int pass = 0; pass < 2; pass++) {
      for (String input : FIXED_INPUTS) {
        checkAll(input);
      }
      warmCache(); // second pass exercises the promoted (cached) paths
    }
  }

  @Test
  void matchesJdkOnRandomInputs() {
    Random random = new Random(42);
    String alphabet = "ab ,;:=.|\t\n\\$";
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < 1500; i++) {
        StringBuilder s = new StringBuilder();
        int length = random.nextInt(14);
        for (int j = 0; j < length; j++) {
          s.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        checkAll(s.toString());
      }
      warmCache();
    }
  }

  @Test
  void literalReplaceByScanningMatchesStringReplace() {
    Random random = new Random(7);
    String alphabet = "ab.:";
    String[] targets = {"a", "ab", "..", ".", "::", "aba", "b:"};
    String[] replacements = {"", "x", "xyz", "a"};
    for (int i = 0; i < 3000; i++) {
      StringBuilder s = new StringBuilder();
      int length = random.nextInt(12);
      for (int j = 0; j < length; j++) {
        s.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      String input = s.toString();
      for (String target : targets) {
        for (String replacement : replacements) {
          assertEquals(
              input.replace(target, replacement),
              StringRewrites.replaceLiteralByScanning(input, target, replacement));
        }
      }
    }
    String unmatched = "nothing to see";
    assertSame(unmatched, StringRewrites.replaceLiteralByScanning(unmatched, "zz", "y"));
  }

  @Test
  void nullArgumentsBehaveLikeJdk() {
    assertSameOutcome(() -> ((String) null).split(","), () -> StringRewrites.split(null, ","));
    assertSameOutcome(() -> "a".split(null), () -> StringRewrites.split("a", null));
    assertSameOutcome(
        () -> "a".replaceAll(null, "x"), () -> StringRewrites.replaceAll("a", null, "x"));
    assertSameOutcome(
        () -> "a".replaceAll("a", null), () -> StringRewrites.replaceAll("a", "a", null));
    assertSameOutcome(
        () -> "a".replaceFirst("a", null), () -> StringRewrites.replaceFirst("a", "a", null));
    assertSameOutcome(() -> "a".matches(null), () -> StringRewrites.matches("a", null));
    assertSameOutcome(
        () -> Pattern.matches("a", null), () -> StringRewrites.patternMatches("a", null));
  }

  @Test
  void unmatchedLiteralReturnsTheSameInstance() {
    String s = "no match here";
    assertSame(s.replaceAll("zz", "y"), StringRewrites.replaceAll(s, "zz", "y"));
    assertSame(s.replaceFirst("zz", "y"), StringRewrites.replaceFirst(s, "zz", "y"));
    assertSame(s.split("::")[0], StringRewrites.split(s, "::")[0]);
  }

  @Test
  void coldRegexesAreNotRetained() {
    for (int i = 0; i < PatternCache.PROMOTION_THRESHOLD - 1; i++) {
      StringRewrites.matches("abc", "a.c");
    }
    assertFalse(PatternCache.isCached("a.c"));
    StringRewrites.matches("abc", "a.c");
    assertTrue(PatternCache.isCached("a.c"));
  }

  private static void warmCache() {
    for (String regex : REGEXES) {
      for (int i = 0; i < PatternCache.PROMOTION_THRESHOLD + 1; i++) {
        PatternCache.get(regex);
      }
    }
  }

  private static void checkAll(String input) {
    for (String regex : REGEXES) {
      for (int limit : LIMITS) {
        assertSameOutcome(
            () -> input.split(regex, limit), () -> StringRewrites.split(input, regex, limit));
      }
      assertSameOutcome(() -> input.split(regex), () -> StringRewrites.split(input, regex));
      for (String replacement : REPLACEMENTS) {
        assertSameOutcome(
            () -> input.replaceAll(regex, replacement),
            () -> StringRewrites.replaceAll(input, regex, replacement));
        assertSameOutcome(
            () -> input.replaceFirst(regex, replacement),
            () -> StringRewrites.replaceFirst(input, regex, replacement));
      }
      assertSameOutcome(() -> input.matches(regex), () -> StringRewrites.matches(input, regex));
      assertSameOutcome(
          () -> Pattern.matches(regex, input), () -> StringRewrites.patternMatches(regex, input));
    }
  }

  private static void assertSameOutcome(Callable<?> expected, Callable<?> actual) {
    Object expectedResult;
    Object actualResult;
    try {
      expectedResult = expected.call();
    } catch (Throwable e) {
      expectedResult = describe(e);
    }
    try {
      actualResult = actual.call();
    } catch (Throwable e) {
      actualResult = describe(e);
    }
    if (expectedResult instanceof String[] && actualResult instanceof String[]) {
      assertArrayEquals((String[]) expectedResult, (String[]) actualResult);
    } else {
      assertEquals(expectedResult, actualResult);
    }
  }

  private static List<String> describe(Throwable e) {
    List<String> d = new ArrayList<>(Arrays.asList(e.getClass().getName()));
    if (!(e instanceof NullPointerException)) {
      d.add(String.valueOf(e.getMessage())); // helpful NPE messages name the receiver variable
    }
    return d;
  }
}
