package datadog.trace.bootstrap.jit;

import static datadog.trace.bootstrap.jit.Outcomes.assertSameOutcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
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
    "\uD800",
    "\uDC00",
    "\uD800\uDC00",
    "a\uDC00",
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
    "\uD800\uDC00",
    "a\uD800\uDC00b\uDC00c\uD800",
    "\uDC00\uD800",
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
    String alphabet = "ab ,;:=.|\t\n\\$\uD800\uDC00";
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
  void surrogateLiteralsAreNotPlainLiterals() {
    // java.util.regex never starts a match inside a surrogate pair; indexOf does
    String pair = "\uD800\uDC00";
    assertEquals(pair, pair.replaceAll("\uDC00", "x"));
    assertEquals(1, pair.indexOf("\uDC00"));
    assertEquals(pair, StringRewrites.replaceAll(pair, "\uDC00", "x"));
    assertEquals(pair, StringRewrites.replaceAll(pair, "\uD800", "x"));
    assertEquals(pair + "x", StringRewrites.replaceFirst(pair + "\uDC00", "\uDC00", "x"));
    assertNull(RegexShape.literal("\uDC00"));
    assertNull(RegexShape.literal("a\uD800\uDC00"));
  }

  @Test
  void replaceMatchesJdk() {
    Random random = new Random(11);
    String alphabet = "ab.$\\\uD800\uDC00";
    String[] targets = {
      "", "a", "ab", ".", "$", "\\", "\uD800", "\uDC00", "\uD800\uDC00", "a\uDC00"
    };
    String[] replacements = {"", "x", "$0", "\\", "\uDC00", "xyz"};
    for (int i = 0; i < 2000; i++) {
      StringBuilder s = new StringBuilder();
      int length = random.nextInt(10);
      for (int j = 0; j < length; j++) {
        s.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      String input = s.toString();
      for (String target : targets) {
        for (String replacement : replacements) {
          checkReplace(input, target, replacement);
          checkReplace(input, new StringBuilder(target), new StringBuilder(replacement));
        }
      }
    }
  }

  /** Each toString() runs once, in the original order, and a null receiver calls none. */
  @Test
  void replaceHasTheSameSideEffectsAsJdk() {
    String[] values = {null, "", "a", "\uDC00", "x$"};
    String[] receivers = {null, "", "aXa", "\uD800\uDC00"};
    for (String receiver : receivers) {
      for (String target : values) {
        for (String replacement : values) {
          for (int nulls = 0; nulls < 4; nulls++) {
            boolean nullTarget = (nulls & 1) != 0;
            boolean nullReplacement = (nulls & 2) != 0;
            List<String> expectedLog = new ArrayList<>();
            List<String> actualLog = new ArrayList<>();
            CharSequence t1 = nullTarget ? null : new Recording("t", target, expectedLog);
            CharSequence r1 = nullReplacement ? null : new Recording("r", replacement, expectedLog);
            CharSequence t2 = nullTarget ? null : new Recording("t", target, actualLog);
            CharSequence r2 = nullReplacement ? null : new Recording("r", replacement, actualLog);
            assertSameOutcome(
                () -> receiver.replace(t1, r1), () -> StringRewrites.replace(receiver, t2, r2));
            assertEquals(expectedLog, actualLog);
            if (null != receiver) {
              expectedLog.clear();
              actualLog.clear();
              assertSameOutcome(
                  () -> jdk8Replace(receiver, t1, r1),
                  () -> StringRewrites.replaceAsJdk8(receiver, t2, r2));
              assertEquals(expectedLog, actualLog);
            }
          }
        }
      }
    }
  }

  private static void checkReplace(String input, CharSequence target, CharSequence replacement) {
    assertSameOutcome(
        () -> input.replace(target, replacement),
        () -> StringRewrites.replace(input, target, replacement));
    assertSameOutcome(
        () -> jdk8Replace(input, target, replacement),
        () -> StringRewrites.replaceAsJdk8(input, target, replacement));
    if (input.replace(target, replacement) == input) {
      assertSame(input, StringRewrites.replace(input, target, replacement));
    }
    if (jdk8Replace(input, target, replacement) == input) {
      assertSame(input, StringRewrites.replaceAsJdk8(input, target, replacement));
    }
  }

  /** The body of String.replace(CharSequence, CharSequence) on JDK 8. */
  private static String jdk8Replace(String s, CharSequence target, CharSequence replacement) {
    return Pattern.compile(target.toString(), Pattern.LITERAL)
        .matcher(s)
        .replaceAll(Matcher.quoteReplacement(replacement.toString()));
  }

  /** A CharSequence that logs its toString() calls; other methods are never expected. */
  static final class Recording implements CharSequence {
    private final String name;
    private final String value;
    private final List<String> log;

    Recording(String name, String value, List<String> log) {
      this.name = name;
      this.value = value;
      this.log = log;
    }

    @Override
    public int length() {
      log.add(name + ".length");
      return value.length();
    }

    @Override
    public char charAt(int index) {
      log.add(name + ".charAt");
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      log.add(name + ".subSequence");
      return value.subSequence(start, end);
    }

    @Override
    public String toString() {
      log.add(name + ".toString");
      return value;
    }
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
}
