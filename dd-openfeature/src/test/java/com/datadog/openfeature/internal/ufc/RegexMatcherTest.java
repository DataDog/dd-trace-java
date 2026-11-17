package com.datadog.openfeature.internal.ufc;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.junit.jupiter.params.provider.ValueSource;

class RegexMatcherTest {

  static Stream<Arguments> classifications() {
    return Stream.of(
        Arguments.of("", Kind.ALWAYS, null),
        Arguments.of(".*", Kind.ALWAYS, null),
        Arguments.of(".+", Kind.ANY_CHARACTER, null),
        Arguments.of("zach", Kind.CONTAINS, asList("zach")),
        Arguments.of("@example\\.com", Kind.CONTAINS, asList("@example.com")),
        Arguments.of("abc|def", Kind.CONTAINS, asList("abc", "def")),
        Arguments.of("^abc", Kind.STARTS_WITH, asList("abc")),
        Arguments.of("abc$", Kind.ENDS_WITH, asList("abc")),
        Arguments.of("^abc$", Kind.EQUALS, asList("abc")),
        Arguments.of(".*\\@(a|b)$", Kind.ENDS_WITH, asList("@a", "@b")),
        Arguments.of(
            ".*@(alpha|bravo)\\.(io|net)$",
            Kind.ENDS_WITH,
            asList("@alpha.io", "@alpha.net", "@bravo.io", "@bravo.net")),
        Arguments.of("(?:x|y)z.*", Kind.CONTAINS, asList("xz", "yz")),
        Arguments.of("^(us|eu)$", Kind.EQUALS, asList("us", "eu")),
        Arguments.of(".*?abc", Kind.CONTAINS, asList("abc")),
        Arguments.of("(?u)\\bmañana\\b", Kind.WORD_BOUNDARY, null),
        Arguments.of("\\bfoo", Kind.WORD_BOUNDARY, null),
        Arguments.of("\\b(foo|bar)\\b", Kind.WORD_BOUNDARY, null),
        // Not literal: a dot, a class, a quantifier, flags, or an anchor next to .*.
        Arguments.of(".*@example.com", Kind.ENGINE, null),
        Arguments.of("^[a-zA-Z0-9._%+-]+@site\\.test$", Kind.ENGINE, null),
        Arguments.of("ab+c", Kind.ENGINE, null),
        Arguments.of("(?i)abc", Kind.ENGINE, null),
        Arguments.of("(?iu)abc", Kind.ENGINE, null),
        Arguments.of("^.*abc", Kind.ENGINE, null),
        Arguments.of("abc.*$", Kind.ENGINE, null),
        Arguments.of("^a|b$", Kind.ENGINE, null),
        Arguments.of("(a|)b", Kind.ENGINE, null),
        Arguments.of("\\d+", Kind.ENGINE, null),
        Arguments.of("\\pN$", Kind.ENGINE, null),
        Arguments.of("\\x41$", Kind.ENGINE, null));
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
        asList(
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
            "tab\\there",
            "\\pL$",
            "\\p{Lu}c$",
            "\\x41$",
            "\\x{41}bc$",
            "^\\x41b",
            "\\101$");
    final List<String> inputs = inputs();
    for (final String pattern : patterns) {
      final RegexMatcher matcher = RegexMatcher.compile(pattern);
      final com.google.re2j.Pattern engine =
          com.google.re2j.Pattern.compile(RegexMatcher.Dialect.toRe2(pattern));
      for (final String input : inputs) {
        assertEquals(
            engine.matcher(input).find(),
            matcher.matches(input),
            "pattern=" + pattern + " input=" + input.replace("\n", "\\n"));
      }
    }
  }

  private static List<String> inputs() {
    final List<String> inputs =
        new ArrayList<>(
            asList(
                "",
                "\n",
                "\r",
                "a\n",
                "abc",
                "abc\n",
                "xabcx",
                "Abc",
                "AAbc",
                "Abbc",
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
                "bad char@site.test",
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
                "x@example.com",
                "abc5",
                "N",
                "A",
                "Éc"));
    final SplittableRandom rnd = new SplittableRandom(1);
    final String alphabet = "abcdefxyzAN5@.|$()\n\tuszach";
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

  /** Escapes longer than one character are not literal suffixes or prefixes. */
  @Test
  void multiCharacterEscapesAreNotLiterals() {
    assertTrue(RegexMatcher.compile("\\pN$").matches("abc5"));
    assertTrue(RegexMatcher.compile("\\p{Lu}c$").matches("Ac"));
    assertTrue(RegexMatcher.compile("\\x41$").matches("A"));
    assertTrue(RegexMatcher.compile("\\x{41}bc$").matches("Abc"));
    assertTrue(RegexMatcher.compile("\\101$").matches("A"));
    assertEquals(asList("bc"), engine("\\x{41}bc$").requiredSuffixes());
    assertNull(engine("\\pN$").requiredSuffixes());
  }

  /** The Rust regex dialect, where it differs from java.util.regex and from plain RE2/J. */
  @Test
  void usesTheRustDialect() {
    // $ matches only at the end of the input, not before a final line feed.
    assertFalse(RegexMatcher.compile("a$").matches("a\n"));
    assertFalse(RegexMatcher.compile("^[a-z]+@site\\.test$").matches("john@site.test\n"));
    // . matches any character except a line feed.
    assertTrue(RegexMatcher.compile("^a.b$").matches("a\rb"));
    assertFalse(RegexMatcher.compile("^a.b$").matches("a\nb"));
    assertFalse(RegexMatcher.compile(".+").matches("\n\n"));
    assertTrue(RegexMatcher.compile(".+").matches("\n\r"));
    // Bracketed POSIX classes.
    assertTrue(RegexMatcher.compile("^[[:alnum:]]+$").matches("abc123"));
    assertFalse(RegexMatcher.compile("^[[:alnum:]]+$").matches("abc-123"));
    // \d and \s are Unicode classes.
    assertTrue(RegexMatcher.compile("^\\d+$").matches("١٢٣"));
    assertTrue(RegexMatcher.compile("^[\\d-]+$").matches("١-2"));
    assertFalse(RegexMatcher.compile("^\\D+$").matches("a١"));
    assertTrue(RegexMatcher.compile("^a\\sb$").matches("a b"));
    assertTrue(RegexMatcher.compile("^a[\\s]b$").matches("a　b"));
    assertFalse(RegexMatcher.compile("^\\S+$").matches("a b"));
  }

  @Test
  void removesTheUnicodeFlag() {
    assertEquals("abc", RegexMatcher.Dialect.toRe2("(?u)abc"));
    assertEquals("(?i)abc", RegexMatcher.Dialect.toRe2("(?iu)abc"));
    assertEquals("(?i)abc", RegexMatcher.Dialect.toRe2("(?i-u)abc"));
    assertEquals("(?:abc)", RegexMatcher.Dialect.toRe2("(?u:abc)"));
    assertEquals("(?:abc)", RegexMatcher.Dialect.toRe2("(?-u:abc)"));
    assertEquals("(?:abc)", RegexMatcher.Dialect.toRe2("(?:abc)"));
    assertEquals("(?P<name>x)", RegexMatcher.Dialect.toRe2("(?P<name>x)"));
    assertEquals("[(?u)]x", RegexMatcher.Dialect.toRe2("[(?u)]x"));
    assertEquals("\\(?u)", RegexMatcher.Dialect.toRe2("\\(?u)"));
    assertTrue(RegexMatcher.compile("(?i-u)ABC").matches("abc"));
  }

  /** Word boundaries use the Rust (Unicode) definition of a word character. */
  @Test
  void wordBoundaryIsUnicodeAware() {
    final RegexMatcher manana = RegexMatcher.compile("(?u)\\bmañana\\b");
    assertTrue(manana.matches("llega mañana temprano"));
    assertTrue(manana.matches("mañana"));
    assertFalse(manana.matches("mañanas"));
    // A non-ASCII letter is a word character, so there is no boundary before "m".
    assertFalse(manana.matches("ñmañana"));
    final RegexMatcher prefix = RegexMatcher.compile("\\bfoo");
    assertTrue(prefix.matches("a foobar"));
    assertFalse(prefix.matches("afoo"));
    assertFalse(prefix.matches("éfoo"));
    assertTrue(prefix.matches("-foo"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*@datadoghq.com",
        "(abc",
        "[abc",
        // java.util.regex accepts these, but the Rust regex crate does not.
        "(?=a)a",
        "(?<!a)b",
        "(a)\\1",
        "(?>a)",
        "\\p{Alnum}",
        "\\Qa.b\\E",
        // RE2/J reads these as literal characters, but the Rust regex crate does not.
        "[a-z&&[^aeiou]]",
        "[[a-z]--[aeiou]]",
        "[a~~b]"
      })
  void rejectsInvalidPatterns(final String pattern) {
    assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile(pattern), pattern);
  }

  /** Patterns that make java.util.regex backtrack finish in linear time. */
  @Test
  void backtrackingPatternsFinishFast() {
    final String as = repeat('a', 5_000);
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          assertFalse(RegexMatcher.compile("(a+)+$").matches(as + "!"));
          assertFalse(RegexMatcher.compile("^(a|aa)+$").matches(as + "!"));
          assertFalse(RegexMatcher.compile("(a*)*b").matches(as));
          assertFalse(RegexMatcher.compile(".*a.*a.*a.*a.*b").matches(as));
          assertFalse(RegexMatcher.compile(".*.*.*.*.*=").matches(as));
          assertFalse(RegexMatcher.compile(".*@(x|y)\\.com$").matches(as + "@z.com"));
          assertFalse(RegexMatcher.compile("^[a-z]+@site\\.test$").matches(as + "@other.test"));
        });
  }

  @Test
  void findsRequiredPrefixAndSuffixes() {
    assertEquals(
        asList("@site.test"), engine("^[a-zA-Z0-9._%+-]+@site\\.test$").requiredSuffixes());
    assertEquals(
        asList("@alpha.io", "@alpha.net", "@bravo.io", "@bravo.net"),
        engine("^u[0-9]{1,3}@(?:alpha|bravo)\\.(?:io|net)$").requiredSuffixes());
    // A quantified group is not a required suffix; only the text after it is.
    assertEquals(asList(".com"), engine("^x(?:a|b)+\\.com$").requiredSuffixes());
    assertEquals(asList("abc"), engine("x+abc$").requiredSuffixes());
    assertNull(engine("[a-z]+$").requiredSuffixes());
    assertNull(engine("^[a-zA-Z0-9._%+-]+@site\\.test$").requiredPrefix());
    assertEquals("ab", engine("^ab+c").requiredPrefix());
    assertEquals("a", engine("^ab?c").requiredPrefix());
    assertEquals("ab", engine("^abc*d").requiredPrefix());
    // {n,m} can allow zero repetitions, so the quantified character is not required.
    assertNull(engine("^a{2}b").requiredPrefix());
    assertNull(engine("^ab+c|d").requiredPrefix());
    assertNull(engine("(?i)^abc+").requiredPrefix());
    // A quantifier applies to a whole surrogate pair.
    assertEquals("a", engine("^a😀?\\d").requiredPrefix());
    assertTrue(RegexMatcher.compile("^a😀?\\d").matches("a1"));
  }

  @Test
  void requiredSuffixesCannotBeChanged() {
    final List<String> suffixes = engine("^[a-z]+@(?:a|b)$").requiredSuffixes();
    assertThrows(UnsupportedOperationException.class, () -> suffixes.set(0, "x"));
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
