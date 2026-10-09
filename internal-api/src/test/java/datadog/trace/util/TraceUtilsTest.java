package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class TraceUtilsTest {

  @TableTest({
    "scenario         | service                                                                                                          | expected                                                                                              ",
    "null service     |                                                                                                                  | 'unnamed-service'                                                                                     ",
    "empty service    | ''                                                                                                               | 'unnamed-service'                                                                                     ",
    "valid service    | 'good'                                                                                                           | 'good'                                                                                                ",
    "invalid char     | 'bad$service'                                                                                                    | 'bad_service'                                                                                         ",
    "too long service | 'Too$Long$.Too$Long$.Too$Long$.Too$Long$.Too$Long$.Too$Long$.Too$Long$.Too$Long$.Too$Long$.Too$Long$.Too$Long$.' | 'too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.'"
  })
  void normalizeServiceName(String service, String expected) {
    assertEquals(expected, TraceUtils.normalizeServiceName(service));
  }

  @TableTest({
    "scenario                     | name                                                                                                             | expected                                                                                    ",
    "null name                    |                                                                                                                  | 'unnamed_operation'                                                                         ",
    "empty name                   | ''                                                                                                               | 'unnamed_operation'                                                                         ",
    "valid name                   | 'good'                                                                                                           | 'good'                                                                                      ",
    "dash becomes underscore      | 'bad-name'                                                                                                       | 'bad_name'                                                                                  ",
    "too long name truncated      | 'Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.' | 'Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.'",
    "already normalized           | 'pylons.controller'                                                                                              | 'pylons.controller'                                                                         ",
    "dash before dot              | 'trace-api.request'                                                                                              | 'trace_api.request'                                                                         ",
    "single slash                 | '/'                                                                                                              | 'unnamed_operation'                                                                         ",
    "leading non-alpha stripped   | '{çà]test'                                                                                                       | 'test'                                                                                      ",
    "underscores before dot       | 'l___.'                                                                                                          | 'l.'                                                                                        ",
    "collapsed underscores        | 'a___b'                                                                                                          | 'a_b'                                                                                       ",
    "trailing underscores trimmed | 'a___'                                                                                                           | 'a'                                                                                         ",
    "no alphabetic characters     | '🐨🐶 繋'                                                                                                        | 'unnamed_operation'                                                                         "
  })
  void normalizeOperationName(String name, String expected) {
    assertEquals(expected, TraceUtils.normalizeOperationName(name).toString());
  }

  @TableTest({
    "scenario                            | tag                                               | expected                             ",
    "null tag                            |                                                   | ''                                   ",
    "empty tag                           | ''                                                | ''                                   ",
    "already normalized                  | 'ok'                                              | 'ok'                                 ",
    "single space                        | ' '                                               | ''                                   ",
    "leading hash stripped               | '#test_starting_hash'                             | 'test_starting_hash'                 ",
    "uppercase folded                    | 'TestCAPSandSuch'                                 | 'testcapsandsuch'                    ",
    "weird characters replaced           | 'Test Conversion Of Weird !@#$%^&**() Characters' | 'test_conversion_of_weird_characters'",
    "leading symbols stripped            | '$#weird_starting'                                | 'weird_starting'                     ",
    "colon allowed                       | 'allowed:c0l0ns'                                  | 'allowed:c0l0ns'                     ",
    "leading digit stripped              | '1love'                                           | 'love'                               ",
    "unicode preserved                   | 'ünicöde'                                         | 'ünicöde'                            ",
    "unicode with colon preserved        | 'ünicöde:metäl'                                   | 'ünicöde:metäl'                      ",
    "emoji and cjk collapsed             | 'Data🐨dog🐶 繋がっ⛰てて'                        | 'data_dog_繋がっ_てて'               ",
    "surrounding spaces trimmed          | ' spaces   '                                      | 'spaces'                             ",
    "mixed symbols and spaces collapsed  | ' #hashtag!@#spaces #__<>#  '                     | 'hashtag_spaces'                     ",
    "leading colon allowed               | ':testing'                                        | ':testing'                           ",
    "leading underscore stripped         | '_foo'                                            | 'foo'                                ",
    "leading colons allowed              | ':::test'                                         | ':::test'                            ",
    "repeated underscores collapsed      | 'contiguous_____underscores'                      | 'contiguous_underscores'             ",
    "trailing underscore stripped        | 'foo_'                                            | 'foo'                                ",
    "long s preserved                    | 'ſodd_ſcaseſ'                                     | 'ſodd_ſcaseſ'                        ",
    "trademark and o-slash folded        | '™Ö™Ö™™Ö™'                                        | 'ö_ö_ö'                              ",
    "mixed case with colon and unicode   | 'AlsO:ök'                                         | 'also:ök'                            ",
    "leading colon with underscore       | ':still_ok'                                       | ':still_ok'                          ",
    "leading underscores stripped        | '___trim'                                         | 'trim'                               ",
    "leading digits and dot stripped     | '12.:trim@'                                       | ':trim'                              ",
    "trailing symbols stripped           | '12.:trim@@'                                      | ':trim'                              ",
    "double underscore collapsed         | 'fun:ky__tag/1'                                   | 'fun:ky_tag/1'                       ",
    "at sign replaced                    | 'fun:ky@tag/2'                                    | 'fun:ky_tag/2'                       ",
    "repeated at signs collapsed         | 'fun:ky@@@tag/3'                                  | 'fun:ky_tag/3'                       ",
    "slash and dot allowed               | 'tag:1/2.3'                                       | 'tag:1/2.3'                          ",
    "leading dashes and symbols stripped | '---fun:k####y_ta@#g/1_@@#'                       | 'fun:k_y_ta_g/1'                     ",
    "unicode with parens and hash        | 'AlsO:œ#@ö))œk'                                   | 'also:œ_ö_œk'                        "
  })
  void normalizeTag(String tag, String expected) {
    assertEquals(expected, TraceUtils.normalizeTag(tag));
  }

  @TableTest({
    "scenario                  | tag                                               | expected                             ",
    "null tag value            |                                                   | ''                                   ",
    "empty tag value           | ''                                                | ''                                   ",
    "already normalized        | 'ok'                                              | 'ok'                                 ",
    "single space              | ' '                                               | ''                                   ",
    "uppercase folded          | 'TestCAPSandSuch'                                 | 'testcapsandsuch'                    ",
    "weird characters replaced | 'Test Conversion Of Weird !@#$%^&**() Characters' | 'test_conversion_of_weird_characters'",
    "dash and dot preserved    | '1.55.0-SNAPSHOT'                                 | '1.55.0-snapshot'                    ",
    "comma replaced            | 'a,b'                                             | 'a_b'                                "
  })
  void normalizeTagValue(String tag, String expected) {
    assertEquals(expected, TraceUtils.normalizeTagValue(tag));
  }

  @TableTest({
    "scenario                     | spanType                                                                                                           | expected                                                                                              ",
    "null span type               |                                                                                                                    |                                                                                                       ",
    "empty span type              | ''                                                                                                                 | ''                                                                                                    ",
    "valid span type              | 'ok'                                                                                                               | 'ok'                                                                                                  ",
    "too long span type truncated | 'VeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLong' | 'VeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVery'"
  })
  void normalizeSpanType(String spanType, String expected) {
    CharSequence normalized = TraceUtils.normalizeSpanType(spanType);
    assertEquals(expected, normalized == null ? null : normalized.toString());
  }

  @ParameterizedTest(name = "{index}: {0}")
  @TableTest({
    "scenario  | env  | expected",
    "null env  |      | 'none'  ",
    "empty env | ''   | 'none'  ",
    "valid env | 'ok' | 'ok'    "
  })
  @MethodSource("normalizeEnvTruncationArguments")
  void normalizeEnv(String env, String expected) {
    assertEquals(expected, TraceUtils.normalizeEnv(env));
  }

  private static Stream<Arguments> normalizeEnvTruncationArguments() {
    return Stream.of(arguments(repeat("a", 300), repeat("a", 200)));
  }

  @TableTest({
    "scenario           | httpStatusCode | expected",
    "lower bound valid  | 100            | true    ",
    "typical valid code | 404            | true    ",
    "out of range       | 600            | false   "
  })
  void isValidStatusCode(int httpStatusCode, boolean expected) {
    assertEquals(expected, TraceUtils.isValidStatusCode(httpStatusCode));
  }

  private static String repeat(String str, int length) {
    StringBuilder builder = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      builder.append(str);
    }
    return builder.toString();
  }
}
