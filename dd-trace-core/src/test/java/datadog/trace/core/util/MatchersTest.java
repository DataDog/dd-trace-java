package datadog.trace.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

public class MatchersTest {

  @ParameterizedTest(name = "match-all scenarios must return an any matcher [{index}] {0}")
  @NullSource
  @ValueSource(strings = {"*", "**"})
  public void matchAllScenariosReturnAnyMatcher(String glob) {
    assertInstanceOf(Matchers.AnyMatcher.class, Matchers.compileGlob(glob));
  }

  @ParameterizedTest(name = "pattern without * or ? must be an EqualsMatcher [{index}] {0}")
  @ValueSource(strings = {"a", "ogre", "bcoho34e2"})
  public void patternWithoutWildcardsMustBeInsensitiveEqualsMatcher(String glob) {
    assertInstanceOf(Matchers.InsensitiveEqualsMatcher.class, Matchers.compileGlob(glob));
  }

  @ParameterizedTest(name = "pattern with either * or ? must be a PatternMatcher [{index}] {0}")
  @ValueSource(strings = {"?", "foo*", "*bar", "F?oB?r", "F?o*", "?*", "*?"})
  public void patternWithWildcardsMustBePatternMatcher(String glob) {
    assertInstanceOf(Matchers.PatternMatcher.class, Matchers.compileGlob(glob));
  }

  @ParameterizedTest(name = "an exact matcher is self matching [{index}] {0}")
  @ValueSource(strings = {"", "a", "abc", "cde"})
  public void exactMatcherIsSelfMatching(String pattern) {
    assertTrue(Matchers.compileGlob(pattern).matches(pattern));
  }

  @TableTest({
    "scenario                        | pattern | value       | matches",
    "fo? matches Foo                 | 'fo?'   | 'Foo'       | true   ",
    "Fo? matches Foo                 | 'Fo?'   | 'Foo'       | true   ",
    "Fo? no match Fooo               | 'Fo?'   | 'Fooo'      | false  ",
    "Fo* matches Fo                  | 'Fo*'   | 'Fo'        | true   ",
    "Fo* no match Fa                 | 'Fo*'   | 'Fa'        | false  ",
    "F*B?r matches FooBar            | 'F*B?r' | 'FooBar'    | true   ",
    "F*B?r no match FooFar           | 'F*B?r' | 'FooFar'    | false  ",
    "f*b?r matches FooBar            | 'f*b?r' | 'FooBar'    | true   ",
    "empty matches empty             | ''      | ''          | true   ",
    "empty no match non-empty        | ''      | 'non-empty' | false  ",
    "star matches foo                | '*'     | 'foo'       | true   ",
    "doublestar matches foo          | '**'    | 'foo'       | true   ",
    "triple q matches foo            | '???'   | 'foo'       | true   ",
    "brackets match brackets         | '[a-z]' | '[a-z]'     | true   ",
    "brackets no match a             | '[a-z]' | 'a'         | false  ",
    "abc brackets match abc brackets | '[abc]' | '[abc]'     | true   ",
    "AbC brackets match abc brackets | '[AbC]' | '[abc]'     | true   ",
    "abc brackets no match a         | '[abc]' | 'a'         | false  ",
    "notab brackets match notab      | '[!ab]' | '[!ab]'     | true   ",
    "notab brackets no match c       | '[!ab]' | 'c'         | false  ",
    "caret matches caret             | '^'     | '^'         | true   ",
    "parens match parens             | '()'    | '()'        | true   ",
    "star in parens matches dash     | '(*)'   | '(-)'       | true   ",
    "dollar matches dollar           | '$'     | '$'         | true   "
  })
  @ParameterizedTest(name = "a pattern matcher test (strings) [{index}] {0}")
  public void patternMatcherTestStrings(
      String scenario, String pattern, String value, boolean matches) {
    assertMatcherResult(pattern, value, matches);
  }

  static Stream<Object[]> patternMatcherComplexCases() {
    return Stream.of(
        new Object[] {"Fo? matches StringBuilder Foo", "Fo?", new StringBuilder("Foo"), true},
        new Object[] {"Fo? matches StringBuilder foo", "Fo?", new StringBuilder("foo"), true},
        new Object[] {"Foo matches StringBuilder foo", "Foo", new StringBuilder("foo"), true},
        new Object[] {"bar no match StringBuilder Baz", "bar", new StringBuilder("Baz"), false},
        new Object[] {"star matches Boolean true", "*", Boolean.TRUE, true},
        new Object[] {"true matches Boolean true", "true", Boolean.TRUE, true},
        new Object[] {"false matches Boolean false", "false", Boolean.FALSE, true},
        new Object[] {"TRUE matches Boolean true", "TRUE", Boolean.TRUE, true},
        new Object[] {"FALSE matches Boolean false", "FALSE", Boolean.FALSE, true},
        new Object[] {"True matches Boolean true", "True", Boolean.TRUE, true},
        new Object[] {"False matches Boolean false", "False", Boolean.FALSE, true},
        new Object[] {"T* matches Boolean true", "T*", Boolean.TRUE, true},
        new Object[] {"F* matches Boolean false", "F*", Boolean.FALSE, true},
        new Object[] {"star matches int 20", "*", 20, true},
        new Object[] {"20 matches int 20", "20", 20, true},
        new Object[] {"-20 matches int -20", "-20", -20, true},
        new Object[] {"star matches byte 20", "*", (byte) 20, true},
        new Object[] {"20 matches byte 20", "20", (byte) 20, true},
        new Object[] {"star matches short 20", "*", (short) 20, true},
        new Object[] {"20 matches short 20", "20", (short) 20, true},
        new Object[] {"star matches long 20", "*", 20L, true},
        new Object[] {"20 matches long 20", "20", 20L, true},
        new Object[] {"star matches float 20", "*", 20F, true},
        new Object[] {"20 matches float 20", "20", 20F, true},
        new Object[] {"star matches double 20", "*", 20D, true},
        new Object[] {"20 matches double 20", "20", 20D, true},
        new Object[] {"20 matches BigInteger 20", "20", new BigInteger("20"), true},
        new Object[] {"20 matches BigDecimal 20", "20", new BigDecimal("20"), true},
        new Object[] {"2* no match float 20.1", "2*", 20.1F, false},
        new Object[] {"2* no match double 20.1", "2*", 20.1D, false},
        new Object[] {"2* no match BigDecimal 20.1", "2*", new BigDecimal("20.1"), false},
        new Object[] {"star matches anonymous object", "*", newAnonymousObject(), true},
        new Object[] {"doublestar matches anonymous object", "**", newAnonymousObject(), true},
        new Object[] {"q no match anonymous object", "?", newAnonymousObject(), false},
        new Object[] {"Ab brackets match StringBuffer ab", "[Ab]", new StringBuffer("[ab]"), true},
        new Object[] {"star matches null", "*", null, true},
        new Object[] {"q no match null", "?", null, false});
  }

  private static Object newAnonymousObject() {
    return new Object() {};
  }

  @ParameterizedTest(name = "a pattern matcher test (complex) [{index}] {0}")
  @MethodSource("patternMatcherComplexCases")
  public void patternMatcherTestComplex(
      String scenario, String pattern, Object value, boolean matches) {
    assertMatcherResult(pattern, value, matches);
  }

  private void assertMatcherResult(String pattern, Object value, boolean matches) {
    Matcher matcher = Matchers.compileGlob(pattern);
    if (matches) {
      assertTrue(matcher.matches(value));
    } else {
      assertFalse(matcher.matches(value));
    }
  }
}
