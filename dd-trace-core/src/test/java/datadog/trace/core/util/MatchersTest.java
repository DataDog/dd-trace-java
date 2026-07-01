package datadog.trace.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MatchersTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"*", "**"})
  void matchAllScenariosMustReturnAnAnyMatcher(String glob) {
    assertInstanceOf(Matchers.AnyMatcher.class, Matchers.compileGlob(glob));
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "ogre", "bcoho34e2"})
  void patternWithoutStarOrQuestionMustBeAnEqualsMatcher(String glob) {
    assertInstanceOf(Matchers.InsensitiveEqualsMatcher.class, Matchers.compileGlob(glob));
  }

  @ParameterizedTest
  @ValueSource(strings = {"?", "foo*", "*bar", "F?oB?r", "F?o*", "?*", "*?"})
  void patternWithEitherStarOrQuestionMustBeAPatternMatcher(String glob) {
    assertInstanceOf(Matchers.PatternMatcher.class, Matchers.compileGlob(glob));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "a", "abc", "cde"})
  void anExactMatcherIsSelfMatching(String pattern) {
    assertTrue(Matchers.compileGlob(pattern).matches(pattern));
  }

  static Stream<Arguments> aPatternMatcherTestArguments() {
    return Stream.of(
        arguments("fo? matches Foo", "fo?", "Foo", true),
        arguments("Fo? matches Foo", "Fo?", "Foo", true),
        arguments("Fo? matches StringBuilder Foo", "Fo?", new StringBuilder("Foo"), true),
        arguments("Fo? matches StringBuilder foo", "Fo?", new StringBuilder("foo"), true),
        arguments("Foo matches StringBuilder foo", "Foo", new StringBuilder("foo"), true),
        arguments("bar does not match StringBuilder Baz", "bar", new StringBuilder("Baz"), false),
        arguments("Fo? does not match Fooo", "Fo?", "Fooo", false),
        arguments("Fo* matches Fo", "Fo*", "Fo", true),
        arguments("Fo* does not match Fa", "Fo*", "Fa", false),
        arguments("F*B?r matches FooBar", "F*B?r", "FooBar", true),
        arguments("F*B?r does not match FooFar", "F*B?r", "FooFar", false),
        arguments("f*b?r matches FooBar", "f*b?r", "FooBar", true),
        arguments("* matches true", "*", true, true),
        arguments("true matches true", "true", true, true),
        arguments("false matches false", "false", false, true),
        arguments("TRUE matches true", "TRUE", true, true),
        arguments("FALSE matches false", "FALSE", false, true),
        arguments("True matches true", "True", true, true),
        arguments("False matches false", "False", false, true),
        arguments("T* matches true", "T*", true, true),
        arguments("F* matches false", "F*", false, true),
        arguments("empty matches empty", "", "", true),
        arguments("empty does not match non-empty", "", "non-empty", false),
        arguments("* matches foo", "*", "foo", true),
        arguments("** matches foo", "**", "foo", true),
        arguments("??? matches foo", "???", "foo", true),
        arguments("* matches int 20", "*", 20, true),
        arguments("20 matches int 20", "20", 20, true),
        arguments("-20 matches int -20", "-20", -20, true),
        arguments("* matches byte 20", "*", (byte) 20, true),
        arguments("20 matches byte 20", "20", (byte) 20, true),
        arguments("* matches short 20", "*", (short) 20, true),
        arguments("20 matches short 20", "20", (short) 20, true),
        arguments("* matches long 20", "*", 20L, true),
        arguments("20 matches long 20", "20", 20L, true),
        arguments("* matches float 20", "*", 20F, true),
        arguments("20 matches float 20", "20", 20F, true),
        arguments("* matches double 20", "*", 20D, true),
        arguments("20 matches double 20", "20", 20D, true),
        arguments("20 matches BigInteger 20", "20", new BigInteger("20"), true),
        arguments("20 matches BigDecimal 20", "20", new BigDecimal("20"), true),
        arguments("2* does not match float 20.1", "2*", 20.1F, false),
        arguments("2* does not match double 20.1", "2*", 20.1D, false),
        arguments("2* does not match BigDecimal 20.1", "2*", new BigDecimal("20.1"), false),
        arguments("* matches arbitrary Object", "*", new Object(), true),
        arguments("** matches arbitrary Object", "**", new Object(), true),
        arguments("? does not match arbitrary Object", "?", new Object(), false),
        arguments("* matches null", "*", null, true),
        arguments("? does not match null", "?", null, false),
        arguments("[a-z] matches [a-z]", "[a-z]", "[a-z]", true),
        arguments("[a-z] does not match a", "[a-z]", "a", false),
        arguments("[abc] matches [abc]", "[abc]", "[abc]", true),
        arguments("[AbC] matches [abc]", "[AbC]", "[abc]", true),
        arguments("[Ab] matches StringBuffer [ab]", "[Ab]", new StringBuffer("[ab]"), true),
        arguments("[abc] does not match a", "[abc]", "a", false),
        arguments("[!ab] matches [!ab]", "[!ab]", "[!ab]", true),
        arguments("[!ab] does not match c", "[!ab]", "c", false),
        arguments("^ matches ^", "^", "^", true),
        arguments("() matches ()", "()", "()", true),
        arguments("(*) matches (-)", "(*)", "(-)", true),
        arguments("$ matches $", "$", "$", true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("aPatternMatcherTestArguments")
  void aPatternMatcherTest(String scenario, String pattern, Object value, boolean matches) {
    Matcher matcher = Matchers.compileGlob(pattern);

    assertEquals(matches, matcher.matches(value));
  }

  @Test
  void anyMatcherMatchesString() {
    assertTrue(Matchers.ANY.matches("hello"));
  }

  @Test
  void anyMatcherMatchesCharSequence() {
    assertTrue(Matchers.ANY.matches((CharSequence) new StringBuilder("world")));
  }

  @Test
  void anyMatcherMatchesBoolean() {
    assertTrue(Matchers.ANY.matches(true));
  }

  @Test
  void anyMatcherMatchesByte() {
    assertTrue(Matchers.ANY.matches((byte) 1));
  }

  @Test
  void anyMatcherMatchesShort() {
    assertTrue(Matchers.ANY.matches((short) 2));
  }

  @Test
  void anyMatcherMatchesInt() {
    assertTrue(Matchers.ANY.matches(42));
  }

  @Test
  void anyMatcherMatchesLong() {
    assertTrue(Matchers.ANY.matches(100L));
  }

  @Test
  void anyMatcherMatchesFloat() {
    assertTrue(Matchers.ANY.matches(1.5f));
  }

  @Test
  void anyMatcherMatchesDouble() {
    assertTrue(Matchers.ANY.matches(3.14));
  }

  @Test
  void anyMatcherMatchesBigInteger() {
    assertTrue(Matchers.ANY.matches(new BigInteger("123")));
  }

  @Test
  void anyMatcherMatchesBigDecimal() {
    assertTrue(Matchers.ANY.matches(new BigDecimal("1.23")));
  }
}
