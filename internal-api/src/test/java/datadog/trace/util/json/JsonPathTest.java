package datadog.trace.util.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JsonPathTest {

  private static JsonPath.Builder jp() {
    return JsonPath.Builder.start();
  }

  private static PathCursor p() {
    return new PathCursor(10);
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("matchingArguments")
  void matching(JsonPath pattern, PathCursor path) {
    assertTrue(pattern.matches(path));
  }

  private static Stream<Arguments> matchingArguments() {
    return Stream.of(
        arguments(jp().name("foo").name("bar").build(), p().push("foo").push("bar")),
        arguments(
            jp().name("foo").name("bar").name("baz").build(),
            p().push("foo").push("bar").push("baz")),
        arguments(
            jp().name("foo").anyChild().name("baz").build(),
            p().push("foo").push("bar").push("baz")),
        arguments(
            jp().name("foo").anyChild().name("baz").build(), p().push("foo").push(42).push("baz")),
        arguments(jp().anyChild().build(), p().push("phoneNumbers")),
        arguments(jp().anyChild().anyChild().build(), p().push("foo").push("bar")),
        arguments(jp().name("keys").index(3).build(), p().push("keys").push(3)),
        arguments(jp().name("keys").index(3).name("b").build(), p().push("keys").push(3).push("b")),
        arguments(
            jp().anyDesc().name("password").build(),
            p().push("foo").push("bar").push(33).push("password")),
        arguments(jp().name("foo").anyDesc().name("bar").build(), p().push("foo").push("bar")),
        arguments(
            jp().name("foo").anyDesc().name("bar").build(),
            p().push("foo").push("bar").push(33).push("bar")),
        arguments(
            jp().name("foo").anyDesc().name("bar").build(),
            p().push("foo").push("bar").push(33).push("bar")),
        arguments(
            jp().name("foo").anyDesc().name("bar").build(),
            p().push("foo").push("bar").push(33).push("bar")),
        arguments(
            jp().name("foo").anyDesc().name("bar").build(),
            p().push("foo").push("baz").push(33).push("bar")),
        arguments(
            jp().anyDesc().name("number").anyDesc().name("area").anyDesc().name("code").build(),
            p().push("number").push("area").push("code")),
        arguments(
            jp().anyDesc().name("number").anyDesc().name("area").anyDesc().name("code").build(),
            p().push(2).push("number").push("props").push(0).push("area").push("code")));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("nonMatchingArguments")
  void nonMatching(JsonPath pattern, PathCursor path) {
    assertFalse(pattern.matches(path));
  }

  private static Stream<Arguments> nonMatchingArguments() {
    return Stream.of(
        arguments(jp().name("foo").name("bar").build(), p().push("foo").push("Bar")),
        arguments(jp().name("foo").name("bar").name("baz").build(), p().push("foo").push("bar")),
        arguments(jp().name("foo").anyChild().build(), p().push("bar").push("baz")),
        arguments(
            jp().name("foo").anyChild().name("baz").build(),
            p().push("Foo").push("bar").push("baz")),
        arguments(
            jp().name("foo").anyChild().name("baz").build(),
            p().push("foo").push("bar").push("Baz")),
        arguments(
            jp().name("foo").anyChild().name("baz").build(),
            p().push("foo").push("bar").push(3).push("baz")),
        arguments(
            jp().name("foo").anyChild().name("baz").build(),
            p().push("foo").push("bar").push("bar").push("baz")),
        arguments(jp().anyChild().build(), p().push("foo").push("bar")),
        arguments(jp().anyChild().anyChild().build(), p().push("foo")),
        arguments(jp().anyChild().anyChild().build(), p().push("foo").push("bar").push("baz")),
        arguments(jp().name("keys").index(3).build(), p().push("keys").push(4)),
        arguments(jp().name("keys").index(3).build(), p().push("keys").push("3")),
        arguments(jp().name("keys").index(3).name("b").build(), p().push("keys").push(3).push("a")),
        arguments(jp().name("keys").index(3).name("b").build(), p().push("keys").push(5).push("b")),
        arguments(jp().name("keyz").index(3).name("b").build(), p().push("keys").push(5).push("b")),
        arguments(
            jp().anyDesc().name("password").build(),
            p().push("foo").push("bar").push(33).push("Password")),
        arguments(
            jp().name("foo").anyDesc().name("bar").build(),
            p().push("foo").push("bar").push(33).push("baz")),
        arguments(
            jp().anyDesc().name("number").anyDesc().name("AREA").anyDesc().name("code").build(),
            p().push("number").push("area").push("code")),
        arguments(
            jp().anyDesc().name("number").anyDesc().name("area").anyDesc().name("CODE").build(),
            p().push(2).push("number").push("props").push(0).push("area").push("code")));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("printNormalizedArguments")
  void printNormalized(JsonPath pattern, String normalized) {
    assertEquals(normalized, pattern.toString());
  }

  private static Stream<Arguments> printNormalizedArguments() {
    return Stream.of(
        arguments(
            jp().name("phoneNumbers").anyChild().name("number").build(),
            "$['phoneNumbers'][*]['number']"),
        arguments(
            jp().name("phoneNumbers").anyChild().name("number").build(),
            "$['phoneNumbers'][*]['number']"),
        arguments(
            jp().name("phoneNumbers").anyChild().name("number").build(),
            "$['phoneNumbers'][*]['number']"),
        arguments(jp().anyDesc().name("number").build(), "$..['number']"),
        arguments(
            jp().name("foo").anyDesc().name("bar").anyDesc().name("number").build(),
            "$['foo']..['bar']..['number']"),
        arguments(
            jp().name("phoneNumbers").index(3).name("number").build(),
            "$['phoneNumbers'][3]['number']"),
        arguments(
            jp().name("phoneNumbers").name("3").name("number").build(),
            "$['phoneNumbers']['3']['number']"));
  }
}
