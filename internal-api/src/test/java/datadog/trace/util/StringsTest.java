package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class StringsTest {

  @TableTest({
    "scenario                    | className             | expected             ",
    "dotted class name           | 'foo.bar.Class'       | 'foo/bar/Class.class'",
    "already a resource name     | 'foo/bar/Class.class' | 'foo/bar/Class.class'",
    "unqualified class name      | 'Class'               | 'Class.class'        ",
    "already a resource name too | 'Class.class'         | 'Class.class'        "
  })
  void resourceNameFromClass(String className, String expected) {
    assertEquals(expected, Strings.getResourceName(className));
  }

  @TableTest({
    "scenario                 | resourceName          | expected       ",
    "resource name            | 'foo/bar/Class.class' | 'foo.bar.Class'",
    "already a class name     | 'foo.bar.Class'       | 'foo.bar.Class'",
    "unqualified resource     | 'Class.class'         | 'Class'        ",
    "already a class name too | 'Class'               | 'Class'        "
  })
  void classNameFromResource(String resourceName, String expected) {
    assertEquals(expected, Strings.getClassName(resourceName));
  }

  @TableTest({
    "scenario          | resourceName    | expected       ",
    "dotted class name | 'foo.bar.Class' | 'foo/bar/Class'",
    "unqualified name  | 'Class'         | 'Class'        "
  })
  void internalNameFromClassName(String resourceName, String expected) {
    assertEquals(expected, Strings.getInternalName(resourceName));
  }

  @TableTest({
    "scenario          | className       | expected ",
    "dotted class name | 'foo.bar.Class' | 'foo.bar'",
    "unqualified name  | 'Class'         | ''       "
  })
  void packageNameFromClass(String className, String expected) {
    assertEquals(expected, Strings.getPackageName(className));
  }

  @TableTest({
    "scenario           | className       | expected",
    "dotted class name  | 'foo.bar.Class' | 'Class' ",
    "unqualified name   | 'Class'         | 'Class' ",
    "deeply nested name | 'a.b.c.D'       | 'D'     "
  })
  void simpleNameFromClass(String className, String expected) {
    assertEquals(expected, Strings.getSimpleName(className));
  }

  @Test
  void envvarFromProperty() {
    assertEquals("FOO_BAR_QUX", ConfigStrings.toEnvVar("foo.bar-qux"));
  }

  @TableTest({
    "scenario                   | string       | delimiter | replacement | expected          | expectedFirst",
    "delimiter is a substring   | 'teststring' | 'tstr'    | 'a'         | 'tesaing'         | 'tesaing'    ",
    "single char, multiple hits | 'teststring' | 't'       | 'a'         | 'aesasaring'      | 'aeststring' ",
    "single char, remove all    | 'teststring' | 't'       | ''          | 'essring'         | 'eststring'  ",
    "delimiter not found        | 'teststring' | 'z'       | 's'         | 'teststring'      | 'teststring' ",
    "replacement longer         | 'tetetetete' | 't'       | 'te'        | 'teeteeteeteetee' | 'teetetetete'",
    "replacement shorter        | 'tetetetete' | 'te'      | 't'         | 'ttttt'           | 'ttetetete'  ",
    "overlapping delimiter      | 'tetetetete' | 'tet'     | 'e'         | 'eeeete'          | 'eetetete'   "
  })
  void replaceStrings(
      String string, String delimiter, String replacement, String expected, String expectedFirst) {
    assertEquals(expected, Strings.replace(string, delimiter, replacement));
    assertEquals(expectedFirst, Strings.replaceFirst(string, delimiter, replacement));
  }

  @TableTest({
    "scenario     | input                                            | expected                                                          ",
    "english text | 'the quick brown fox jumps over the lazy dog'    | '05c6e08f1d9fdafa03147fcb8f82f124c76d2f70e3d989dc8aadb5e7d7450bec'",
    "swedish text | 'det kommer bli bättre, du kommer andas lättare' | '9e6215a16fc8968bf3ba29d81f028f7d4bbf22ccc59ae87a0e36a8085f1c2968'"
  })
  void sha256(String input, String expected) throws NoSuchAlgorithmException {
    assertEquals(expected, Strings.sha256(input));
  }

  @TableTest({
    "scenario                      | input         | limit | expected",
    "null input                    |               | 4     |         ",
    "empty string                  | ''            | 4     | ''      ",
    "under limit                   | 'hi'          | 4     | 'hi'    ",
    "multibyte string within limit | 'hélló'       | 5     | 'hélló' ",
    "multibyte string truncated    | 'hélló wórld' | 5     | 'hélló' "
  })
  void truncate(String input, int limit, String expected) {
    if (expected == null) {
      assertNull(Strings.truncate(input, limit));
    } else {
      assertEquals(expected, Strings.truncate(input, limit));
    }
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("isNotBlankAndIsBlankArguments")
  void isNotBlankAndIsBlank(String description, String input, boolean expected) {
    boolean notBlank = Strings.isNotBlank(input);
    boolean isBlank = Strings.isBlank(input);

    assertEquals(expected, notBlank);
    assertEquals(!notBlank, isBlank);
  }

  private static Stream<Arguments> isNotBlankAndIsBlankArguments() {
    return Stream.of(
        arguments("null", null, false),
        arguments("empty string", "", false),
        arguments("single space", " ", false),
        arguments("tab", "\t", false),
        arguments("newline", "\n", false),
        arguments("mixed whitespace", " \t\n ", false),
        arguments("single letter", "a", true),
        arguments("letter surrounded by spaces", " a ", true),
        arguments("mixed whitespace and digits", "\n\t123 ", true),
        arguments("CJK characters surrounded by spaces", " 測 試    ", true),
        arguments("surrogate pairs surrounded by spaces", "   𐢀𐢀𐢀𐢀", true));
  }

  @TableTest({
    "scenario      | value                   | expected                                    ",
    "null value    |                         |                                             ",
    "empty string  | ''                      | ''                                          ",
    "email address | 'zouzou@sansgluten.com' | '7A6F757A6F754073616E73676C7574656E2E636F6D'"
  })
  void hexadecimalEncoding(String value, String expected) {
    String encoded = Strings.toHexString(value == null ? null : value.getBytes());

    if (value == null) {
      assertNull(encoded);
    } else {
      assertEquals(expected.toLowerCase(), encoded.toLowerCase());
    }
  }

  @TableTest({
    "scenario                  | first | second | expected",
    "both non-null, keep first | 'a'   | 'b'    | 'a'     ",
    "second is null            | 'a'   |        | 'a'     ",
    "first is null             |       | 'b'    | 'b'     ",
    "first is empty            | ''    | 'b'    | 'b'     ",
    "both null                 |       |        |         ",
    "both empty                | ''    | ''     |         "
  })
  void coalesce(String first, String second, String expected) {
    if (expected == null) {
      assertNull(Strings.coalesce(first, second));
    } else {
      assertEquals(expected, Strings.coalesce(first, second));
    }
  }
}
