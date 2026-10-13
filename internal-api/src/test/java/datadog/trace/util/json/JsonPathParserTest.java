package datadog.trace.util.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class JsonPathParserTest {

  private static JsonPath.Builder jp() {
    return JsonPath.Builder.start();
  }

  @TableTest({
    "Scenario               | paths                | expected                                ",
    "single path            | [$.a]                | \"[$['a']]\"                            ",
    "multiple paths         | [$.a.b.c, $.x.y.z]   | \"[$['a']['b']['c'], $['x']['y']['z']]\"",
    "one valid one invalid  | [$.BarFoo, invalid]  | \"[$['BarFoo']]\"                       ",
    "single all keyword     | [all]                | \"[]\"                                  ",
    "duplicate all keywords | [all, all]           | \"[]\"                                  ",
    "two invalid paths      | [invalid1, invalid2] | \"[]\"                                  "
  })
  void parseJsonPathsForPayloadTagging(List<String> paths, String expected) {
    assertEquals(expected, JsonPathParser.parseJsonPaths(paths).toString());
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("parseCorrectJsonPathPatternsArguments")
  void parseCorrectJsonPathPatterns(String path, JsonPath expected)
      throws JsonPathParser.ParseError {
    assertEquals(expected.toString(), JsonPathParser.parse(path).toString());
  }

  private static Stream<Arguments> parseCorrectJsonPathPatternsArguments() {
    return Stream.of(
        arguments("$.a", jp().name("a").build()),
        arguments("$.BarFoo", jp().name("BarFoo").build()),
        arguments("$.*", jp().anyChild().build()),
        arguments("$[*]", jp().anyChild().build()),
        arguments("$[* ]", jp().anyChild().build()),
        arguments("$[ *]", jp().anyChild().build()),
        arguments("$..b", jp().anyDesc().name("b").build()),
        arguments("$..*", jp().anyDesc().anyChild().build()),
        arguments("$..fooBar", jp().anyDesc().name("fooBar").build()),
        arguments("$..[*]", jp().anyDesc().anyChild().build()),
        arguments("$..[0]", jp().anyDesc().index(0).build()),
        arguments(
            "$.phoneNumbers.*.number", jp().name("phoneNumbers").anyChild().name("number").build()),
        arguments(
            "$.phoneNumbers[*].number",
            jp().name("phoneNumbers").anyChild().name("number").build()),
        arguments("$..number", jp().anyDesc().name("number").build()),
        arguments(
            "$.foo..bar..number",
            jp().name("foo").anyDesc().name("bar").anyDesc().name("number").build()),
        arguments(
            "$[\"foo\"]..[\"bar\"]..[\"number\"]",
            jp().name("foo").anyDesc().name("bar").anyDesc().name("number").build()),
        arguments(
            "$.phoneNumbers[3].number", jp().name("phoneNumbers").index(3).name("number").build()),
        arguments(
            "$.phoneNumbers[\"3\"].number",
            jp().name("phoneNumbers").name("3").name("number").build()),
        arguments(
            "$.phoneNumbers[ \"3\" ].number",
            jp().name("phoneNumbers").name("3").name("number").build()),
        arguments("$.*.*", jp().anyChild().anyChild().build()),
        arguments("$[\" a\"]", jp().name(" a").build()),
        arguments("$[\"a \"]", jp().name("a ").build()));
  }

  @TableTest({
    "path                                 | pos | msg                                                             ",
    "''                                   | 0   | must start with '$                                              ",
    "c                                    | 0   | must start with '$                                              ",
    "$.                                   | 1   | must not end with a '.'                                         ",
    "$..                                  | 2   | must not end with a '.'                                         ",
    "$...                                 | 3   | More than two '.' in a row                                      ",
    "'$.a '                               | 3   | No spaces allowed in property names.                            ",
    "$. a                                 | 2   | No spaces allowed in property names.                            ",
    "$.f o                                | 3   | No spaces allowed in property names.                            ",
    "$*                                   | 0   | JsonPath must start with                                        ",
    "$a                                   | 0   | JsonPath must start with                                        ",
    "$foo                                 | 0   | JsonPath must start with                                        ",
    "$[                                   | 1   | Expecting in brackets a property, an array index, or a wildcard.",
    "'$[ '                                | 1   | Expecting in brackets a property, an array index, or a wildcard.",
    "$[[                                  | 1   | Expecting in brackets a property, an array index, or a wildcard.",
    "$[-                                  | 1   | Expecting in brackets a property, an array index, or a wildcard.",
    "$[1                                  | 1   | Expecting in brackets a property, an array index, or a wildcard.",
    "$[00                                 | 1   | Expecting in brackets a property, an array index, or a wildcard.",
    "$['                                  | 1   | Property has not been closed - missing closing '                ",
    "$[\"                                 | 1   | Property has not been closed - missing closing \"               ",
    "$[*                                  | 3   | Expected ']'                                                    ",
    "$.**                                 | 2   | More than one '*' in a row                                      ",
    "$[()]                                | 1   | Expecting in brackets a property, an array index, or a wildcard.",
    "$[\"a\"][..][\"b\"]                  | 6   | Expecting in brackets a property, an array index, or a wildcard.",
    "$.(@.length-1)                       | 2   | Expressions are not supported.                                  ",
    "$.[*]                                | 1   | \"'.' can't go before '['\"                                     ",
    "$.[\"foo\"]..[\"bar\"]..[\"number\"] | 1   | \"'.' can't go before '['\"                                     ",
    "$.phoneNumbers.[*].number            | 14  | \"'.' can't go before '['\"                                     ",
    "$.phoneNumbers.[3].number            | 14  | \"'.' can't go before '['\"                                     ",
    "$.a[1,2]                             | 3   | Expecting in brackets a property, an array index, or a wildcard.",
    "$.foo[-100]                          | 5   | Expecting in brackets a property, an array index, or a wildcard.",
    "$.foo[9999999999999999]              | 5   | Invalid array index. Must be an integer.                        ",
    "$[\"\\\"]                            | 3   | Escape character is not supported in property name.             ",
    "$[\"abc]                             | 1   | Property has not been closed - missing closing \"               ",
    "$['abc]                              | 1   | Property has not been closed - missing closing '                ",
    "$[\"abc                              | 1   | Property has not been closed - missing closing \"               ",
    "$[\"a,\"]                            | 4   | Comma is not allowed in property name                           ",
    "$[\"a\",]                            | 5   | Multiple properties are not supported                           ",
    "$[\"a\",\"b\"]                       | 5   | Multiple properties are not supported                           ",
    "$[,\"a\"]                            | 1   | Expecting in brackets a property, an array index, or a wildcard ",
    "$[\"abc\"                            | 1   | Property has not been closed - missing closing ']'              ",
    "'$[\"abc\" '                         | 1   | Property has not been closed - missing closing ']'              "
  })
  @ParameterizedTest(name = "{index}: {0}")
  void expectedParseErrors(String path, int pos, String msg) {
    JsonPathParser.ParseError error =
        assertThrows(JsonPathParser.ParseError.class, () -> JsonPathParser.parse(path));
    assertTrue(error.getMessage().contains(msg));
    assertEquals(pos, error.position);
  }
}
