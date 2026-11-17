package com.datadog.openfeature.internal.ufc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.RegexMatcher.Kind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RegexMatcherTest {

  static Stream<Arguments> classifications() {
    return Stream.of(
        Arguments.of("", Kind.ALWAYS, null),
        Arguments.of(".*", Kind.ALWAYS, null),
        Arguments.of(".+", Kind.ANY_CHARACTER, null),
        Arguments.of("zach", Kind.CONTAINS, Arrays.asList("zach")),
        Arguments.of("@example\\.com", Kind.CONTAINS, Arrays.asList("@example.com")),
        Arguments.of("abc|def", Kind.CONTAINS, Arrays.asList("abc", "def")),
        Arguments.of("^abc", Kind.STARTS_WITH, Arrays.asList("abc")),
        Arguments.of("abc$", Kind.ENDS_WITH, Arrays.asList("abc")),
        Arguments.of("^abc$", Kind.EQUALS, Arrays.asList("abc")),
        Arguments.of(".*\\@(a|b)$", Kind.ENDS_WITH, Arrays.asList("@a", "@b")),
        Arguments.of(
            ".*@(alpha|bravo)\\.(io|net)$",
            Kind.ENDS_WITH,
            Arrays.asList("@alpha.io", "@alpha.net", "@bravo.io", "@bravo.net")),
        Arguments.of("(?:x|y)z.*", Kind.CONTAINS, Arrays.asList("xz", "yz")),
        Arguments.of("^(us|eu)$", Kind.EQUALS, Arrays.asList("us", "eu")),
        Arguments.of(".*?abc", Kind.CONTAINS, Arrays.asList("abc")),
        Arguments.of("(?u)\\bma\u00f1ana\\b", Kind.WORD_BOUNDARY, null),
        Arguments.of("\\bfoo", Kind.WORD_BOUNDARY, null),
        Arguments.of("\\b(foo|bar)\\b", Kind.WORD_BOUNDARY, null),
        Arguments.of("(?iu)abc", Kind.ENGINE, null),
        // Not literal: a dot, a class, a quantifier, flags, or an anchor next to .*.
        Arguments.of(".*@example.com", Kind.ENGINE, null),
        Arguments.of("^[a-zA-Z0-9._%+-]+@site\\.test$", Kind.ENGINE, null),
        Arguments.of("ab+c", Kind.ENGINE, null),
        Arguments.of("(?i)abc", Kind.ENGINE, null),
        Arguments.of("^.*abc", Kind.ENGINE, null),
        Arguments.of("abc.*$", Kind.ENGINE, null),
        Arguments.of("^a|b$", Kind.ENGINE, null),
        Arguments.of("(a|)b", Kind.ENGINE, null),
        Arguments.of("\\d+", Kind.ENGINE, null));
  }

  @ParameterizedTest
  @MethodSource("classifications")
  void classifiesEachPattern(final String pattern, final Kind kind, final List<String> literals) {
    final RegexMatcher matcher = RegexMatcher.compile(pattern);
    assertEquals(kind, matcher.kind(), pattern);
    if (literals != null) {
      assertEquals(literals, ((RegexMatcher.Literals) matcher).literals(), pattern);
    }
  }

  /** Each fast path and prefilter must give the same result as the RE2/J engine alone. */
  @Test
  void fastPathsAgreeWithTheEngine() {
    final List<String> patterns =
        Arrays.asList(
            "",
            ".*",
            ".+",
            ".+?",
            "zach",
            "@example\\.com",
            "abc|def",
            "^abc",
            "abc$",
            "^abc$",
            ".*\\@(a|b)$",
            ".*@(alpha|bravo)\\.(io|net)$",
            "(?:x|y)z.*",
            "^(us|eu)$",
            ".*?abc",
            "abc.*",
            "^ab+c",
            "^abc*d",
            "^a{2}b",
            "x+abc$",
            "^[a-zA-Z0-9._%+-]+@site\\.test$",
            "^u[0-9]{1,3}@(?:alpha|bravo)\\.(?:io|net)$",
            "^x(?:a|b)+\\.com$",
            "\\.test$",
            "a\\$",
            "a\\|b",
            "\\(a\\)",
            "tab\\there");
    final List<String> inputs = inputs();
    for (final String pattern : patterns) {
      final RegexMatcher matcher = RegexMatcher.compile(pattern);
      final com.google.re2j.Pattern engine = com.google.re2j.Pattern.compile(pattern);
      for (final String input : inputs) {
        assertEquals(
            engine.matcher(input).find(),
            matcher.find(input),
            "pattern=" + pattern + " input=" + input.replace("\n", "\\n"));
      }
    }
  }

  private static List<String> inputs() {
    final List<String> inputs =
        new ArrayList<>(
            Arrays.asList(
                "",
                "\n",
                "\r",
                "a\n",
                "abc",
                "abc\n",
                "xabcx",
                "def",
                "zach",
                "zachary",
                "us",
                "eu",
                "use",
                "xz",
                "yzz",
                "user@a",
                "user@b",
                "user@c",
                "u@alpha.io",
                "u@bravo.net",
                "u@alpha.com",
                "u12@alpha.io",
                "u1234@bravo.net",
                "xab.com",
                "x.com",
                "John.Doe+x@site.test",
                "john@site.test\n",
                "bad char!@site.test",
                "abbbc",
                "ac",
                "abd",
                "aab",
                "xxabc",
                "a$",
                "a|b",
                "(a)",
                "tab\there",
                "@example.com",
                "x@example.com"));
    final SplittableRandom rnd = new SplittableRandom(1);
    final String alphabet = "abcdefxyz@.|$()\n\tuszach";
    for (int i = 0; i < 2000; i++) {
      final StringBuilder s = new StringBuilder();
      final int length = rnd.nextInt(12);
      for (int j = 0; j < length; j++) {
        s.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
      }
      inputs.add(s.toString());
    }
    return inputs;
  }

  /** The RE2 and Rust semantics, where they differ from java.util.regex. */
  @Test
  void usesRe2Semantics() {
    // $ matches only at the end of the input, not before a final line feed.
    assertFalse(RegexMatcher.compile("a$").find("a\n"));
    assertFalse(RegexMatcher.compile("^[a-z]+@site\\.test$").find("john@site.test\n"));
    // . matches any character except a line feed.
    assertTrue(RegexMatcher.compile("^a.b$").find("a\rb"));
    assertFalse(RegexMatcher.compile("^a.b$").find("a\nb"));
    assertFalse(RegexMatcher.compile(".+").find("\n\n"));
    assertTrue(RegexMatcher.compile(".+").find("\n\r"));
    // RE2/J supports the bracketed POSIX classes.
    assertTrue(RegexMatcher.compile("^[[:alnum:]]+$").find("abc123"));
    assertFalse(RegexMatcher.compile("^[[:alnum:]]+$").find("abc-123"));
  }

  @Test
  void removesTheUnicodeFlag() {
    assertEquals("abc", RegexMatcher.withoutUnicodeFlag("(?u)abc"));
    assertEquals("(?i)abc", RegexMatcher.withoutUnicodeFlag("(?iu)abc"));
    assertEquals("(?:abc)", RegexMatcher.withoutUnicodeFlag("(?u:abc)"));
    assertEquals("(?:abc)", RegexMatcher.withoutUnicodeFlag("(?:abc)"));
    assertEquals("[(?u)]x", RegexMatcher.withoutUnicodeFlag("[(?u)]x"));
    assertEquals("\\(?u)", RegexMatcher.withoutUnicodeFlag("\\(?u)"));
  }

  /** Word boundaries use the Rust (Unicode) definition of a word character. */
  @Test
  void wordBoundaryIsUnicodeAware() {
    final RegexMatcher manana = RegexMatcher.compile("(?u)\\bma\u00f1ana\\b");
    assertTrue(manana.find("llega ma\u00f1ana temprano"));
    assertTrue(manana.find("ma\u00f1ana"));
    assertFalse(manana.find("ma\u00f1anas"));
    // A non-ASCII letter is a word character, so there is no boundary before "m".
    assertFalse(manana.find("\u00f1ma\u00f1ana"));
    final RegexMatcher prefix = RegexMatcher.compile("\\bfoo");
    assertTrue(prefix.find("a foobar"));
    assertFalse(prefix.find("afoo"));
    assertFalse(prefix.find("\u00e9foo"));
    assertTrue(prefix.find("-foo"));
  }

  @Test
  void matchesAndNotMatchesAreOpposites() {
    final RegexMatcher matcher = RegexMatcher.compile("@example\\.com$");
    assertTrue(matcher.test("a@example.com", true));
    assertFalse(matcher.test("a@example.com", false));
    assertFalse(matcher.test("a@other.com", true));
    assertTrue(matcher.test("a@other.com", false));
  }

  @Test
  void malformedPatternThrows() {
    assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile("*@datadoghq.com"));
    assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile("(abc"));
    assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile("[abc"));
    // java.util.regex accepts these, but the Rust regex crate and RE2/J do not.
    for (final String pattern :
        Arrays.asList("(?=a)a", "(?<!a)b", "(a)\\1", "a*+", "(?>a)", "\\p{Alnum}")) {
      assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile(pattern), pattern);
    }
  }

  /** Patterns that make java.util.regex backtrack finish in linear time. */
  @Test
  void backtrackingPatternsFinishFast() {
    final String as = repeat('a', 5_000);
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          assertFalse(RegexMatcher.compile("(a+)+$").find(as + "!"));
          assertFalse(RegexMatcher.compile("^(a|aa)+$").find(as + "!"));
          assertFalse(RegexMatcher.compile("(a*)*b").find(as));
          assertFalse(RegexMatcher.compile(".*a.*a.*a.*a.*b").find(as));
          assertFalse(RegexMatcher.compile(".*@(x|y)\\.com$").find(as + "@z.com"));
          assertFalse(RegexMatcher.compile("^[a-z]+@site\\.test$").find(as + "@other.test"));
        });
  }

  @Test
  void findsRequiredPrefixAndSuffix() {
    assertEquals(
        Arrays.asList("@site.test"), engine("^[a-zA-Z0-9._%+-]+@site\\.test$").requiredSuffixes());
    assertEquals(
        Arrays.asList("@alpha.io", "@alpha.net", "@bravo.io", "@bravo.net"),
        engine("^u[0-9]{1,3}@(?:alpha|bravo)\\.(?:io|net)$").requiredSuffixes());
    // A quantified group is not a required suffix; only the text after it is.
    assertEquals(Arrays.asList(".com"), engine("^x(?:a|b)+\\.com$").requiredSuffixes());
    assertNull(engine("^[a-zA-Z0-9._%+-]+@site\\.test$").requiredPrefix());
    assertEquals("ab", engine("^ab+c").requiredPrefix());
    assertEquals("a", engine("^ab?c").requiredPrefix());
    assertEquals("ab", engine("^abc*d").requiredPrefix());
    // {n,m} can allow zero repetitions, so the quantified character is not required.
    assertNull(engine("^a{2}b").requiredPrefix());
    assertEquals(Arrays.asList("abc"), engine("x+abc$").requiredSuffixes());
    assertNull(engine("^ab+c|d").requiredPrefix());
    assertNull(engine("(?i)^abc+").requiredPrefix());
    assertNull(engine("[a-z]+$").requiredSuffixes());
  }

  @Test
  void parserSharesOneMatcherForEachPattern() throws Exception {
    final String json =
        "{\"createdAt\":\"2024-04-17T19:40:53.716Z\",\"environment\":{\"name\":\"Test\"},"
            + "\"flags\":{"
            + flag("one", "^[a-z]+@site\\\\.test$")
            + ","
            + flag("two", "^[a-z]+@site\\\\.test$")
            + ","
            + flag("three", "@other\\\\.test$")
            + "}}";
    final ServerConfiguration config = UniversalFlagConfigParser.parse(json.getBytes(UTF_8));
    final RegexMatcher one = regex(config, "one");
    assertSame(one, regex(config, "two"));
    assertEquals(Kind.ENGINE, one.kind());
    assertEquals(Kind.ENDS_WITH, regex(config, "three").kind());
  }

  @Test
  void parserDropsAFlagWithSyntaxThatRustRejects() throws Exception {
    final String json =
        "{\"createdAt\":\"2024-04-17T19:40:53.716Z\",\"environment\":{\"name\":\"Test\"},"
            + "\"flags\":{"
            + flag("lookahead", "(?=a)a")
            + ","
            + flag("valid", "@other\\\\.test$")
            + "}}";
    final ServerConfiguration config = UniversalFlagConfigParser.parse(json.getBytes(UTF_8));
    assertFalse(config.flags.containsKey("lookahead"));
    assertTrue(config.invalidFlags.containsKey("lookahead"));
    assertEquals(Kind.ENDS_WITH, regex(config, "valid").kind());
  }

  private static String flag(final String key, final String pattern) {
    return "\""
        + key
        + "\":{\"key\":\""
        + key
        + "\",\"enabled\":true,\"variationType\":\"BOOLEAN\","
        + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":true}},"
        + "\"allocations\":[{\"key\":\"a\",\"rules\":[{\"conditions\":["
        + "{\"attribute\":\"email\",\"operator\":\"MATCHES\",\"value\":\""
        + pattern
        + "\"}]}],\"splits\":[{\"variationKey\":\"on\",\"shards\":[]}]}]}";
  }

  private static RegexMatcher regex(final ServerConfiguration config, final String key) {
    return config.flags.get(key).allocations.get(0).rules.get(0).conditions.get(0).regex;
  }

  private static RegexMatcher.Engine engine(final String pattern) {
    return (RegexMatcher.Engine) RegexMatcher.compile(pattern);
  }

  private static String repeat(final char c, final int n) {
    final char[] chars = new char[n];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
