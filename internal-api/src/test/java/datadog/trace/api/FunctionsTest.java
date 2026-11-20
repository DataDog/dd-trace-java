package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import constructors.InaccessibleConstructor;
import constructors.NoDefaultConstructor;
import constructors.ThrowingConstructor;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class FunctionsTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("commonStringFunctionsArguments")
  void commonStringFunctions(
      String description, Function<String, String> function, String input, String expected) {
    CharSequence output = function.apply(input);

    assertEquals(expected, output);
  }

  static Stream<Arguments> commonStringFunctionsArguments() {
    return Stream.of(
        arguments("lower case", Functions.LowerCase.INSTANCE, "xYz", "xyz"),
        arguments("to string", new Functions.ToString<String>(), "xYz", "xYz"));
  }

  @TableTest({
    "scenario    | input | expected  ",
    "empty value | ''    | test.     ",
    "some value  | value | test.value"
  })
  void prefixFunction(String input, String expected) {
    Functions.Prefix prefix = new Functions.Prefix("test.");

    CharSequence output = prefix.apply(input);

    assertEquals(UTF8BytesString.create(expected), output);
  }

  @TableTest({
    "scenario    | input | expected  ",
    "empty value | ''    | .test     ",
    "some value  | value | value.test"
  })
  void suffixFunction(String input, String expected) {
    Functions.Suffix suffix = new Functions.Suffix(".test");

    CharSequence output = suffix.apply(input);

    assertEquals(UTF8BytesString.create(expected), output);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("joinStringsArguments")
  void joinStrings(
      String description,
      BiFunction<CharSequence, CharSequence, CharSequence> function,
      String left,
      String right,
      String expected) {
    CharSequence output = function.apply(left, right);

    assertEquals(expected, String.valueOf(output));
  }

  static Stream<Arguments> joinStringsArguments() {
    Function<CharSequence, CharSequence> identity = Function.identity();
    return Stream.of(
        arguments(
            "prefix join with transformer",
            Functions.PrefixJoin.of("~", identity),
            "x",
            "y",
            "x~y"),
        arguments("prefix join", Functions.PrefixJoin.of("~"), "x", "y", "x~y"),
        arguments(
            "suffix join with transformer",
            Functions.SuffixJoin.of("~", identity),
            "x",
            "y",
            "x~y"),
        arguments("suffix join", Functions.SuffixJoin.of("~"), "x", "y", "x~y"));
  }

  @Test
  void encodeUtf8() {
    UTF8BytesString utf8 = Functions.UTF8_ENCODE.apply("foo");

    assertEquals("foo", utf8.toString());
  }

  @Test
  void construct() {
    Function<Object, ConcurrentHashMap> function = newInstanceOf(ConcurrentHashMap.class);

    assertInstanceOf(ConcurrentHashMap.class, function.apply(""));
  }

  @Test
  void badInput() {
    // no default constructor
    assertThrows(
        IllegalStateException.class, () -> Functions.newInstanceOf(NoDefaultConstructor.class));

    // inaccessible
    assertThrows(
        IllegalStateException.class, () -> Functions.newInstanceOf(InaccessibleConstructor.class));

    // will throw
    Function<Object, ThrowingConstructor> function = newInstanceOf(ThrowingConstructor.class);
    assertNull(function.apply(""));
  }

  /** Narrows the wildcard input type of {@link Functions#newInstanceOf} so it can be applied. */
  @SuppressWarnings("unchecked")
  private static <T> Function<Object, T> newInstanceOf(Class<T> type) {
    return (Function<Object, T>) Functions.newInstanceOf(type);
  }
}
