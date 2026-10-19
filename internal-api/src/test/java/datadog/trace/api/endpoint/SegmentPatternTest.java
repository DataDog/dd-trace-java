package datadog.trace.api.endpoint;

import static datadog.trace.api.endpoint.SegmentPattern.simplify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SegmentPatternTest {

  // 20 chars
  private static final String TWENTY_CHARS = "aaaaaaaaaaaaaaaaaaaa";

  @TableTest({
    "scenario         | input  | expected",
    "three digits     | 123    | true    ",
    "four digits      | 9876   | true    ",
    "ten              | 10     | true    ",
    "ninety-nine      | 99     | true    ",
    // Edge cases - should NOT match
    "single digit     | 1      | false   ",
    "zero             | 0      | false   ",
    "leading zero     | 01     | false   ",
    "leading zeros    | 00123  | false   ",
    "contains letters | 123abc | false   ",
    "no digits        | abc    | false   ",
    "empty            | ''     | false   "
  })
  void integerPatternMatchesIntegersWith2PlusDigits(String input, boolean expected) {
    assertEquals(expected, SegmentPattern.INTEGER.matches(input));
  }

  @TableTest({
    "scenario                  | input     | expected",
    "digits only               | 123       | true    ",
    "dash delimiters           | 1-2-3     | true    ",
    "underscore delimiter      | 999_888   | true    ",
    "dot delimiters            | 12.34.56  | true    ",
    "underscore delimiters     | 1_2_3_4   | true    ",
    // Edge cases - should NOT match
    "too short (< 3 chars)     | 12        | false   ",
    "no digits                 | abc       | false   ",
    "no digits only delimiters | ---       | false   ",
    "letters with dash         | user-123  | false   ",
    "letters with underscore   | order_456 | false   ",
    "letters with dot          | item.789  | false   ",
    "letters around digit      | a1b       | false   ",
    "trailing letters          | 123abc    | false   ",
    "empty                     | ''        | false   "
  })
  void intIdPatternMatchesMixedStringsWithDigitsAndDelimiters(String input, boolean expected) {
    assertEquals(expected, SegmentPattern.INT_ID.matches(input));
  }

  @TableTest({
    "scenario                 | input      | expected",
    "lowercase then digits    | abc123     | true    ",
    "digits then uppercase    | 123ABC     | true    ",
    "deadbeef0                | deadbeef0  | true    ",
    "alternating              | A1B2C3     | true    ",
    "all decimal digits       | 0123456789 | true    ",
    "letters then zero        | abcdef0    | true    ",
    // Edge cases - should NOT match
    "no decimal digit (0-9)   | ABCDEF     | false   ",
    "no decimal digit         | abcdef     | false   ",
    "too short (< 6 chars)    | 12345      | false   ",
    "too short                | abc12      | false   ",
    "contains non-hex letters | xyz123     | false   ",
    "contains delimiter       | abc-123    | false   ",
    "empty                    | ''         | false   "
  })
  void hexPatternMatchesHexadecimalStringsWithAtLeastOneDecimalDigit(
      String input, boolean expected) {
    assertEquals(expected, SegmentPattern.HEX.matches(input));
  }

  @TableTest({
    "scenario                        | input       | expected",
    "dash delimiter                  | abc-123     | true    ",
    "underscore delimiter            | def_456     | true    ",
    "no delimiter                    | A1B2C3      | true    ",
    "dot delimiters                  | abc.123.def | true    ",
    "digits with dashes              | 0-1-2-3-4-5 | true    ",
    "letters then digits             | aaa-bbb-111 | true    ",
    // Edge cases - should NOT match
    "too short (< 6 chars)           | abc12       | false   ",
    "no decimal digit                | ABCDEF      | false   ",
    "contains non-hex letter u       | uuid_def456 | false   ",
    "contains non-hex letters        | abc-xyz     | false   ",
    "contains non-hex letters suffix | 123xyz      | false   ",
    "empty                           | ''          | false   "
  })
  void hexIdPatternMatchesHexDelimiterStringsWithAtLeastOneDecimalDigit(
      String input, boolean expected) {
    assertEquals(expected, SegmentPattern.HEX_ID.matches(input));
  }

  @TableTest({
    "scenario                     | input                                 | expected",
    // Long strings (20+ chars)
    "20 chars                     | aaaaaaaaaaaaaaaaaaaa                  | true    ",
    "long string with dashes      | very-long-string-with-many-characters | true    ",
    "20 digits                    | 12345678901234567890                  | true    ",
    // Special characters
    "percent                      | 'param%20value'                       | true    ",
    "ampersand                    | 'user&admin'                          | true    ",
    "apostrophe                   | \"it's\"                              | true    ",
    "parentheses                  | 'func(arg)'                           | true    ",
    "asterisk                     | 'val*2'                               | true    ",
    "plus                         | 'a+b'                                 | true    ",
    "comma                        | 'a,b'                                 | true    ",
    "colon                        | 'key:value'                           | true    ",
    "equals                       | 'a=b'                                 | true    ",
    "at sign                      | 'user@example'                        | true    ",
    // Edge cases - should NOT match
    "< 20 chars, no special chars | short                                 | false   ",
    "digits                       | 123                                   | false   ",
    "dash is not special          | abc-def                               | false   ",
    "empty                        | ''                                    | false   "
  })
  void stringPatternMatchesLongStringsOrStringsWithSpecialCharacters(
      String input, boolean expected) {
    assertEquals(expected, SegmentPattern.STRING.matches(input));
  }

  @TableTest({
    "scenario               | input                                 | expected        ",
    // INTEGER (highest priority)
    "integer 3 digits       | 123                                   | '{param:int}'   ",
    "integer 4 digits       | 9876                                  | '{param:int}'   ",
    // INT_ID
    "int id with dashes     | 1-2-3                                 | '{param:int_id}'",
    "int id with underscore | 999_888                               | '{param:int_id}'",
    // HEX
    "hex lowercase          | abc123def                             | '{param:hex}'   ",
    "hex uppercase          | DEAD0BEEF                             | '{param:hex}'   ",
    // HEX_ID
    "hex id with dash       | abc-123                               | '{param:hex_id}'",
    "hex id with underscore | def_456                               | '{param:hex_id}'",
    // STRING
    "long string            | very-long-string-with-many-characters | '{param:str}'   ",
    "special character      | 'param%20value'                       | '{param:str}'   ",
    // No match - keep original
    "users                  | users                                 | users           ",
    "orders                 | orders                                | orders          ",
    "v1                     | v1                                    | v1              ",
    "api                    | api                                   | api             ",
    "has letters            | user-123                              | user-123        ",
    "has u which is not hex | uuid-abc123                           | uuid-abc123     ",
    // Edge cases
    "empty                  | ''                                    | ''              ",
    "null                   |                                       |                 "
  })
  void simplifyAppliesPatternsInCorrectOrder(String input, String expected) {
    assertEquals(expected, simplify(input));
  }

  @Test
  void patternPriorityEnsuresCorrectReplacement() {
    // "123" could match INT_ID but INTEGER has priority
    assertEquals("{param:int}", simplify("123"));

    // "abc123" could match HEX_ID but HEX has priority (no delimiters)
    assertEquals("{param:hex}", simplify("abc123"));

    // "abc-123" matches HEX_ID (has delimiter)
    assertEquals("{param:hex_id}", simplify("abc-123"));

    // Very long string matches STRING (length priority)
    assertEquals("{param:str}", simplify(TWENTY_CHARS));

    // Numeric string with delimiters matches INT_ID
    assertEquals("{param:int_id}", simplify("1-2-3"));
  }

  @Test
  void allEnumValuesHaveValidPatternsAndReplacements() {
    for (SegmentPattern pattern : SegmentPattern.values()) {
      String replacement = pattern.getReplacement();
      assertNotNull(replacement);
      assertTrue(replacement.startsWith("{param:"));
      assertTrue(replacement.endsWith("}"));
    }
  }
}
