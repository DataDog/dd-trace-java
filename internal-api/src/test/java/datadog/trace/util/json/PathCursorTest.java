package datadog.trace.util.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PathCursorTest {

  private static PathCursor p() {
    return new PathCursor(10);
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("printPathCursorInDotNotationWithCustomPrefixArguments")
  void printPathCursorInDotNotationWithCustomPrefix(
      String description, PathCursor pathCursor, String expected) {
    assertEquals(expected, pathCursor.toString("dd"));
  }

  private static Stream<Arguments> printPathCursorInDotNotationWithCustomPrefixArguments() {
    return Stream.of(
        arguments(
            "string and numeric segments",
            p().push("phoneNumbers").push(10).push("number"),
            "dd.phoneNumbers.10.number"),
        arguments("single segment", p().push("number"), "dd.number"),
        arguments(
            "three string segments",
            p().push("foo").push("bar").push("number"),
            "dd.foo.bar.number"),
        arguments(
            "string and int index segments",
            p().push("phoneNumbers").push(3).push("number"),
            "dd.phoneNumbers.3.number"),
        arguments(
            "string index segment normalized like an int",
            p().push("phoneNumbers").push("3").push("number"),
            "dd.phoneNumbers.3.number"),
        arguments("segment containing a dot is escaped", p().push("foo.bar"), "dd.foo\\.bar"));
  }

  @Test
  void advanceCursorInArray() {
    PathCursor cursor = p();

    cursor.push(0);
    assertEquals(".0", cursor.toString(""));

    cursor.advance();
    assertEquals(".1", cursor.toString(""));

    cursor.advance();
    assertEquals(".2", cursor.toString(""));

    cursor.push(0);
    assertEquals(".2.0", cursor.toString(""));

    cursor.advance();
    assertEquals(".2.1", cursor.toString(""));

    cursor.pop();
    assertEquals(".2", cursor.toString(""));

    cursor.pop();
    assertEquals("", cursor.toString(""));
  }

  @Test
  void advanceCursorBetweenObjectFields() {
    PathCursor cursor = p();

    cursor.push("foo");
    assertEquals(".foo", cursor.toString(""));

    cursor.advance();
    assertEquals("", cursor.toString(""));

    cursor.push("bar");
    assertEquals(".bar", cursor.toString(""));

    cursor.push("baz");
    assertEquals(".bar.baz", cursor.toString(""));

    cursor.advance();
    assertEquals(".bar", cursor.toString(""));

    cursor.advance();
    assertEquals("", cursor.toString(""));
  }

  @Test
  void copyCreatesAnotherObject() {
    PathCursor original = p().push("a").push(3);
    PathCursor copy = original.copy();

    assertNotSame(original, copy);
    assertEquals(original.toString(""), copy.toString(""));
  }

  @Test
  void createsCursorWithBiggerCapacityThanThePath() {
    PathCursor original = p().push("a").push(3).push("b");
    Object[] path = original.toPath();

    assertArrayEquals(new Object[] {"a", 3, "b"}, path);

    PathCursor cursor = new PathCursor(path, 4);
    assertEquals(".a.3.b.c", cursor.push("c").toString(""));
  }

  @Test
  void getItemAtIndexOfPathCursor() {
    PathCursor cursor = p().push("a").push(3);

    assertEquals("a", cursor.get(0));
    assertEquals(3, cursor.get(1));
  }

  @Test
  void popOnEmptyCursorDoesNothing() {
    PathCursor cursor = p();

    cursor.pop();
    assertEquals(0, cursor.length());
    assertEquals("", cursor.toString(""));

    cursor.push("a").pop();
    cursor.pop();
    assertEquals(0, cursor.length());
    assertEquals("", cursor.toString(""));
  }

  @Test
  void advanceOnEmptyCursorDoesNothing() {
    PathCursor cursor = p();

    cursor.advance();
    assertEquals(0, cursor.length());
    assertEquals("", cursor.toString(""));
  }
}
