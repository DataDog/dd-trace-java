package datadog.trace.bootstrap.regex;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@SuppressForbidden
class RegexOpsTest {

  private static final String[] INPUTS = {
    null, "", "a,b,,c,,", ",a,b", "a.b.c", "a|b|c", "x1y22z333", "AbCaBc", "no-match", "𐀀x"
  };

  private static final String[] REGEXES = {
    null,
    "",
    ",",
    ".",
    "\\.",
    "|",
    "\\|",
    "\\d",
    "\\d+",
    "a",
    "ab",
    "(a)(b)?",
    "[",
    "(",
    "*",
    "\\",
    "\uD800",
    "\\\uD800",
    "\\\uDC00",
    "\\u",
    "(?i)abc",
    "\\w*",
  };

  private static final String[] REPLACEMENTS = {null, "", "-", "$1", "$9", "\\", "$", "${name}"};

  private static final int[] LIMITS = {-1, 0, 1, 2, 3};

  @BeforeEach
  void reset() {
    RegexCache.reset();
  }

  static Stream<Arguments> inputsAndRegexes() {
    Stream.Builder<Arguments> b = Stream.builder();
    for (String input : INPUTS) {
      for (String regex : REGEXES) {
        b.add(Arguments.of(input, regex));
      }
    }
    return b.build();
  }

  @ParameterizedTest
  @MethodSource("inputsAndRegexes")
  void matches(String input, String regex) {
    assertSameOutcome(() -> input.matches(regex), () -> RegexOps.matches(input, regex));
  }

  @ParameterizedTest
  @MethodSource("inputsAndRegexes")
  void patternMatches(String input, String regex) {
    assertSameOutcome(
        () -> Pattern.matches(regex, input), () -> RegexOps.patternMatches(regex, input));
  }

  @ParameterizedTest
  @MethodSource("inputsAndRegexes")
  void replaceAllAndFirst(String input, String regex) {
    for (String repl : REPLACEMENTS) {
      assertSameOutcome(
          () -> input.replaceAll(regex, repl), () -> RegexOps.replaceAll(input, regex, repl));
      assertSameOutcome(
          () -> input.replaceFirst(regex, repl), () -> RegexOps.replaceFirst(input, regex, repl));
    }
  }

  @ParameterizedTest
  @MethodSource("inputsAndRegexes")
  void split(String input, String regex) {
    assertSameOutcome(() -> input.split(regex), () -> RegexOps.split(input, regex));
    for (int limit : LIMITS) {
      assertSameOutcome(() -> input.split(regex, limit), () -> RegexOps.split(input, regex, limit));
    }
  }

  @ParameterizedTest
  @MethodSource("inputsAndRegexes")
  void compile(String ignored, String regex) {
    assertSameOutcome(
        () -> describe(Pattern.compile(regex)), () -> describe(RegexOps.compile(regex)));
    for (int flags : new int[] {0, CASE_INSENSITIVE, Pattern.LITERAL, 0x1000_0000}) {
      assertSameOutcome(
          () -> describe(Pattern.compile(regex, flags)),
          () -> describe(RegexOps.compile(regex, flags)));
    }
  }

  @Test
  void compileReusesPatternForRepeatedReference() {
    String regex = "re+use";
    RegexOps.compile(regex);
    Pattern admitted = RegexOps.compile(regex);
    assertSame(admitted, RegexOps.compile(regex));
    assertSame(admitted, RegexCache.get(regex));
  }

  @Test
  void splitFastpathBypassesCache() {
    for (String regex : new String[] {",", "\\.", "\\|", "a", ":"}) {
      for (int i = 0; i < 3; i++) {
        RegexOps.split("a,b.c|d:e", regex);
        RegexOps.split("a,b.c|d:e", regex, 2);
      }
    }
    assertEquals(0, RegexCache.misses() + RegexCache.hits());

    for (int i = 0; i < 3; i++) {
      RegexOps.split("a1b22c", "\\d+");
    }
    assertEquals(3, RegexCache.misses() + RegexCache.hits());
    assertEquals(1, RegexCache.hits());
  }

  @Test
  void splitFastpathConditionMatchesJdkDocs() {
    assertTrue(RegexOps.isSplitFastpath(","));
    assertTrue(RegexOps.isSplitFastpath("a"));
    assertTrue(RegexOps.isSplitFastpath("\\."));
    assertTrue(RegexOps.isSplitFastpath("\\|"));
    assertFalse(RegexOps.isSplitFastpath("."));
    assertFalse(RegexOps.isSplitFastpath("\\"));
    assertFalse(RegexOps.isSplitFastpath("\\d"));
    assertFalse(RegexOps.isSplitFastpath("\\D"));
    assertFalse(RegexOps.isSplitFastpath("\\1"));
    assertFalse(RegexOps.isSplitFastpath("\uD800"));
    assertFalse(RegexOps.isSplitFastpath("\\\uDFFF"));
    assertFalse(RegexOps.isSplitFastpath(""));
    assertFalse(RegexOps.isSplitFastpath("ab"));
  }

  @Test
  void nullReceiverThrowsNpeBeforeRegexIsParsed() {
    assertSameOutcome(() -> ((String) null).matches("("), () -> RegexOps.matches(null, "("));
    assertSameOutcome(
        () -> ((String) null).replaceAll("(", "x"), () -> RegexOps.replaceAll(null, "(", "x"));
    assertSameOutcome(() -> ((String) null).split("("), () -> RegexOps.split(null, "("));
  }

  private static String describe(Pattern p) {
    return p.pattern() + "/" + p.flags() + "/" + p;
  }

  private static void assertSameOutcome(Callable<?> jdk, Callable<?> ops) {
    Outcome expected = run(jdk);
    for (int i = 0; i < 3; i++) {
      Outcome actual = run(ops);
      expected.assertSameAs(actual);
    }
  }

  private static Outcome run(Callable<?> c) {
    try {
      return new Outcome(c.call(), null);
    } catch (Throwable t) {
      return new Outcome(null, t);
    }
  }

  private static final class Outcome {
    final Object value;
    final Throwable error;

    Outcome(Object value, Throwable error) {
      this.value = value;
      this.error = error;
    }

    void assertSameAs(Outcome actual) {
      if (error != null) {
        assertNull(actual.value, () -> "expected " + error + " but got " + render(actual.value));
        assertEquals(error.getClass(), actual.error.getClass());
        if (!(error instanceof NullPointerException) || bothHaveMessages(error, actual.error)) {
          assertEquals(error.getMessage(), actual.error.getMessage());
        }
        return;
      }
      if (actual.error != null) {
        throw new AssertionError(
            "expected " + render(value) + " but got " + actual.error, actual.error);
      }
      if (value instanceof Object[]) {
        assertArrayEquals((Object[]) value, (Object[]) actual.value);
      } else {
        assertEquals(value, actual.value);
      }
    }

    private static boolean bothHaveMessages(Throwable expected, Throwable actual) {
      return expected.getMessage() != null && actual.getMessage() != null;
    }

    private static String render(Object v) {
      return v instanceof Object[] ? Arrays.toString((Object[]) v) : String.valueOf(v);
    }
  }
}
