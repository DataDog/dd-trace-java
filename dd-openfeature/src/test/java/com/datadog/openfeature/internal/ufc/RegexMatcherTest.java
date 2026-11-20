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
import javax.annotation.Nullable;
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
        "[a~~b]",
        "\\<a",
        "a\\>",
        "\\b{start}a",
        "a\\B{end}"
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

  /** RE2/J unrolls counted repetitions and compiles recursively, so the size is limited. */
  @Test
  void rejectsPatternsThatAreTooLarge() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          assertThrows(
              PatternSyntaxException.class, () -> RegexMatcher.compile("((a{1000}){1000}){1000}"));
          assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile("(a{1000}){1000}"));
          assertThrows(
              PatternSyntaxException.class, () -> RegexMatcher.compile("([a-z]{1,100}){1000}"));
          final String deep = repeat('(', 101) + "a" + repeat(')', 101);
          assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile(deep));
        });
    final String nested = repeat('(', 100) + "a" + repeat(')', 100);
    assertTrue(RegexMatcher.compile(nested).matches("a"));
    assertTrue(RegexMatcher.compile("^u[0-9]{1,100}@(a|b)$").matches("u12@a"));
    assertTrue(RegexMatcher.compile("(ab){99}").matches(repeat('x', 1) + "ab".repeat(99)));
    // A brace that is not a repetition is one character.
    assertTrue(RegexMatcher.compile("a{x}").matches("a{x}"));
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

  /** The word characters of the Rust crate: Alphabetic, marks, Nd, Pc and Join_Control. */
  @Test
  void wordCharactersAreThoseOfTheRustCrate() {
    for (final int codePoint : new int[] {'a', 0x200C, 0x200D, 0x0301, 0x20DD, 0x0903, '5', '_'}) {
      assertTrue(
          RegexMatcher.WordBoundary.isWordCharacter(codePoint), Integer.toHexString(codePoint));
    }
    for (final int codePoint : new int[] {' ', '-', 0x20AC, 0x1F600}) {
      assertFalse(
          RegexMatcher.WordBoundary.isWordCharacter(codePoint), Integer.toHexString(codePoint));
    }
    final RegexMatcher alternatives = RegexMatcher.compile("\\b(foo|bar)\\b");
    assertTrue(alternatives.matches("a bar b"));
    assertTrue(alternatives.matches("foofoo foo"));
    assertFalse(alternatives.matches("barfoo"));
    assertEquals(Kind.ENGINE, RegexMatcher.compile("\\b").kind());
    assertEquals(Kind.ENGINE, RegexMatcher.compile("\\b\\b").kind());
    assertEquals(Kind.ENGINE, RegexMatcher.compile("\\bfo.o").kind());
  }

  @Test
  void translatesClassesAndFlags() {
    assertEquals("[\\D]", RegexMatcher.Dialect.toRe2("[\\D]"));
    assertEquals("[\\S]", RegexMatcher.Dialect.toRe2("[\\S]"));
    assertEquals("[\\b]", RegexMatcher.Dialect.toRe2("[\\b]"));
    assertEquals("(?i", RegexMatcher.Dialect.toRe2("(?i"));
    assertTrue(RegexMatcher.compile("^[^]a]+$").matches("bc"));
    assertFalse(RegexMatcher.compile("^[^]a]+$").matches("b]"));
    assertTrue(RegexMatcher.compile("^[]a]+$").matches("]a"));
    assertTrue(RegexMatcher.compile("^[^\\d]+$").matches("abc"));
    assertFalse(RegexMatcher.compile("^[^\\d]+$").matches("a\u0663"));
    assertTrue(RegexMatcher.compile("\\PL$").matches("a1"));
    assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile("abc\\"));
    assertThrows(PatternSyntaxException.class, () -> RegexMatcher.compile("a("));
  }

  @Test
  void classifiesEscapesAndEdgeCases() {
    assertEquals(asList("a\nb"), literals("a\\nb", Kind.CONTAINS));
    assertEquals(asList("a\rb"), literals("a\\rb$", Kind.ENDS_WITH));
    assertEquals(asList("a\fb"), literals("^a\\fb", Kind.STARTS_WITH));
    assertEquals(asList("abc"), literals("abc.*?", Kind.CONTAINS));
    assertEquals(Kind.ENGINE, RegexMatcher.compile("^").kind());
    assertEquals(Kind.ENGINE, RegexMatcher.compile(".a").kind());
    assertEquals(Kind.ENGINE, RegexMatcher.compile("a+?").kind());
    assertEquals(Kind.ENGINE, RegexMatcher.compile("a|").kind());
    assertEquals(Kind.ENGINE, RegexMatcher.compile("((a|b)c)").kind());
    final StringBuilder many = new StringBuilder("a0");
    for (int i = 1; i <= RegexMatcher.MAX_LITERALS; i++) {
      many.append("|a").append(i);
    }
    assertEquals(Kind.ENGINE, RegexMatcher.compile(many.toString()).kind());
    final String eight = "(a|b|c|d|e|f|g|h)";
    assertEquals(Kind.ENGINE, RegexMatcher.compile(eight + eight + "(a|b)").kind());
  }

  /** The lexer assumes that RE2/J accepted the pattern, but stays safe on any input. */
  @Test
  void lexerHandlesMalformedInput() {
    assertNull(RegexMatcher.Lexer.tokenize("a\\"));
    assertNull(RegexMatcher.Lexer.tokenize("[a"));
    assertNull(RegexMatcher.Lexer.tokenize("(?"));
    assertEquals(2, RegexMatcher.Lexer.tokenize("a(").size());
    assertEquals(-1, RegexMatcher.Lexer.classEnd("[^", 0));
    assertEquals(-1, RegexMatcher.Lexer.classEnd("[", 0));
    assertEquals(-1, RegexMatcher.Lexer.classEnd("[[:alpha]", 0));
    assertEquals(-1, RegexMatcher.Lexer.classEnd("[[", 0));
    assertEquals(2, RegexMatcher.Lexer.escapeEnd("\\x", 0));
    assertEquals(2, RegexMatcher.Lexer.escapeEnd("\\p", 0));
    assertEquals(4, RegexMatcher.Lexer.escapeEnd("\\p{L", 0));
    assertEquals(2, RegexMatcher.Lexer.escapeEnd("\\08", 0));
    assertEquals(3, RegexMatcher.Lexer.escapeEnd("\\01", 0));
    assertNull(RegexMatcher.Lexer.splitTopLevel(RegexMatcher.Lexer.tokenize("a)")));
    assertNull(RegexMatcher.Lexer.splitTopLevel(RegexMatcher.Lexer.tokenize("(a")));
    assertFalse(RegexMatcher.Lexer.isSingleBranch(RegexMatcher.Lexer.tokenize("(a")));
    assertEquals(-1, RegexMatcher.Lexer.flatGroupEnd(RegexMatcher.Lexer.tokenize("(a"), 0));
    assertNull(RegexMatcher.FastPath.of(RegexMatcher.Lexer.tokenize("a)")));
    assertNull(RegexMatcher.Prefilter.requiredPrefix(new ArrayList<>()));
    assertEquals("abc", RegexMatcher.Prefilter.requiredPrefix(RegexMatcher.Lexer.tokenize("^abc")));
    assertEquals(
        "a", RegexMatcher.Prefilter.requiredPrefix(RegexMatcher.Lexer.tokenize("^a\uDC00*")));
    assertNull(RegexMatcher.Prefilter.requiredSuffixes(RegexMatcher.Lexer.tokenize("$")));
    assertNull(RegexMatcher.Prefilter.requiredSuffixes(RegexMatcher.Lexer.tokenize("ab")));
    assertEquals(
        asList("abc"),
        RegexMatcher.Prefilter.requiredSuffixes(RegexMatcher.Lexer.tokenize("abc$")));
  }

  @Test
  void requiredSuffixesAreTruncated() {
    final String eight = "(a|b|c|d|e|f|g|h)";
    final RegexMatcher.Engine matcher = engine("^x" + eight + eight + eight + "(i|j)$");
    assertEquals(16, matcher.requiredSuffixes().size());
    assertTrue(matcher.matches("xabci"));
    assertFalse(matcher.matches("xabck"));
  }

  /**
   * The RE2/J compiler recurses once for each unrolled repetition. With less and less stack left,
   * the compile eventually overflows, and the pattern is rejected instead.
   */
  @Test
  void rejectsPatternsThatOverflowTheStack() {
    final PatternSyntaxException error = compileWithLessStack(0);
    assertTrue(error != null && error.getDescription().contains("too complex"), "" + error);
  }

  @Nullable
  private static PatternSyntaxException compileWithLessStack(final int depth) {
    try {
      RegexMatcher.compile("^u[0-9]{1,1000}@x$");
      return compileWithLessStack(depth + 1);
    } catch (final PatternSyntaxException e) {
      return e;
    } catch (final StackOverflowError e) {
      return null;
    }
  }

  @Test
  void estimatesTheUnrolledSize() {
    RegexMatcher.Limits.check("a\\", "a\\");
    RegexMatcher.Limits.check(")", ")");
    RegexMatcher.Limits.check("a{1", "a{1");
    RegexMatcher.Limits.check("a{,3}", "a{,3}");
    RegexMatcher.Limits.check("a{1,x}", "a{1,x}");
    RegexMatcher.Limits.check("a{12345}", "a{12345}");
    RegexMatcher.Limits.check("a{1a}", "a{1a}");
    RegexMatcher.Limits.check("a{ 1}", "a{ 1}");
    final String large = "(a{1000}){99,}";
    assertThrows(PatternSyntaxException.class, () -> RegexMatcher.Limits.check(large, large));
  }

  private static List<String> literals(final String pattern, final Kind kind) {
    final RegexMatcher matcher = RegexMatcher.compile(pattern);
    assertEquals(kind, matcher.kind(), pattern);
    return ((RegexMatcher.Literals) matcher).literals();
  }

  private static RegexMatcher.Engine engine(final String pattern) {
    return (RegexMatcher.Engine)
        RegexMatcher.compileWithoutPrototype(pattern); // the RE2/J engine, not a prototype tier
  }

  private static String repeat(final char c, final int n) {
    final char[] chars = new char[n];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
