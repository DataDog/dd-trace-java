package datadog.trace.api.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SimpleHttpPathNormalizerTest extends DDJavaSpecification {

  private static final SimpleHttpPathNormalizer SIMPLE_PATH_NORMALIZER =
      new SimpleHttpPathNormalizer();

  @TableTest({
    "scenario                | input          | expected      ",
    "empty                   | ''             | /             ",
    "root                    | /              | /             ",
    "simple path             | /search        | /search       ",
    "placeholders            | /users/?/:name | /users/?/:name",
    "relative                | abc            | abc           ",
    "percent sign            | abc%de         | abc%de        ",
    "blank                   | '   '          | /             ",
    "leading spaces          | '   /:userId'  | /:userId      ",
    "leading tab with digits | '\t/90'        | /?            ",
    "leading tab             | '\t/:userId'   | /:userId      "
  })
  void pullsPathFromUrl(String input, String expected) {
    String path = SIMPLE_PATH_NORMALIZER.normalize(input);
    String pathEncoded = SIMPLE_PATH_NORMALIZER.normalize(input, true);

    assertEquals(expected, path);
    assertEquals(expected, pathEncoded);
  }

  @Test
  void doesNotSuppressWhitespaceInNonInitialPositionOfDecodedStrings() {
    String input = " /a b/";
    String output = SIMPLE_PATH_NORMALIZER.normalize(input, false);

    assertEquals("/a b/", output);
  }

  @TableTest({
    "input            | output         ",
    "/1               | /?             ",
    "/9999            | /?             ",
    "/user/1          | /user/?        ",
    "/user/1/         | /user/?/       ",
    "/user/1/repo/50  | /user/?/repo/? ",
    "/user/1/repo/50/ | /user/?/repo/?/"
  })
  void shouldReplaceAllDigits(String input, String output) {
    assertNormalized(input, output);
  }

  @TableTest({
    "input                                            | output              ",
    "/a1/v2                                           | /?/v2               ",
    "/v3/1a                                           | /v3/?               ",
    "/V01/v9/abc/-1                                   | /V01/v9/abc/?       ",
    "/ABC/av-1/b_2/c.3/d4d/v5f/v699/7                 | /ABC/?/?/?/?/?/?/?  ",
    "/user/asdf123/repository/01234567-9ABC-DEF0-1234 | /user/?/repository/?"
  })
  void shouldReplaceSegmentsWithMixedCharacters(String input, String output) {
    assertNormalized(input, output);
  }

  @TableTest({
    "input   ",
    "/v0/    ",
    "/v10/xyz",
    "/a-b    ",
    "/a_b    ",
    "/a.b    ",
    "/a-b/a-b",
    "/a_b/a_b",
    "/a.b/a.b"
  })
  void shouldLeaveOtherSegmentsAlone(String input) {
    assertNormalized(input, input);
  }

  @TableTest({
    "input                                              | output                ",
    "/%AA1/v2                                           | /?/v2                 ",
    "/v3/1a%BB                                          | /v3/?                 ",
    "/V01/v9/abc/%CC-1                                  | /V01/v9/abc/?         ",
    "/A%DD%EE/av-1/b_2/c.3/%FFd4d/v5f/v699/7            | /A%DD%EE/?/?/?/?/?/?/?",
    "/user/asd%A0123/repository/01234567-9ABC-DEF0-1234 | /user/?/repository/?  "
  })
  void shouldHandleEncodedStrings(String input, String output) {
    String normEncoded = SIMPLE_PATH_NORMALIZER.normalize(input, true);

    assertEquals(output, normEncoded);
  }

  private static void assertNormalized(String input, String expected) {
    String norm = SIMPLE_PATH_NORMALIZER.normalize(input);
    String normEncoded = SIMPLE_PATH_NORMALIZER.normalize(input, true);

    assertEquals(expected, norm);
    assertEquals(expected, normEncoded);
  }
}
