package datadog.trace.lambda;

import static datadog.trace.lambda.MultipartSplitter.extractBoundary;
import static datadog.trace.lambda.MultipartSplitter.parameter;
import static datadog.trace.lambda.MultipartSplitter.split;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.lambda.MultipartSplitter.Part;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MultipartSplitterTest {

  private static final int NO_PART_BUDGET_LIMIT = 256;

  @ParameterizedTest(name = "[{index}] {0} -> {1}")
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {
        // case is preserved: the boundary is matched byte-for-byte against the body
        "multipart/form-data; boundary=AbC123        | AbC123",
        // the parameter name is not
        "multipart/form-data; BOUNDARY=xy            | xy",
        // quoted values may hold separators
        "multipart/form-data; boundary=\"a;b c\"     | a;b c",
        // position among the other parameters does not matter
        "multipart/form-data; boundary=xy; charset=x | xy",
        "multipart/form-data; charset=x; boundary=xy | xy",
        // a parameter that merely ends in "boundary" is not one
        "multipart/form-data; xboundary=xy           | NULL",
        "multipart/form-data                         | NULL",
        "multipart/form-data; boundary=              | NULL",
        "NULL                                        | NULL",
      })
  void extractsTheBoundary(String contentType, String expected) {
    assertEquals(expected, extractBoundary(contentType));
  }

  @Test
  void rejectsABoundaryOverSeventyCharacters() {
    String maximum = repeat('a', 70);

    assertEquals(maximum, extractBoundary("multipart/form-data; boundary=" + maximum));
    assertNull(extractBoundary("multipart/form-data; boundary=" + maximum + "a"));
  }

  @ParameterizedTest(name = "[{index}] {1} of {0} -> {2}")
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {
        "form-data; name=user               | name     | user",
        "form-data; name=\"user\"           | name     | user",
        "form-data; name=\"a;b\"            | name     | a;b",
        "form-data; NAME=user               | name     | user",
        "form-data; name=user; charset=utf-8| name     | user",
        // "filename" must not answer a lookup for "name": the match has to start at a parameter
        "form-data; filename=\"f\"; name=u  | name     | u",
        // present but empty, which is what browsers send for an untouched file input
        "form-data; filename=\"\"           | filename | ''",
        "form-data; filename=               | filename | ''",
        "form-data; name=user               | filename | NULL",
        "NULL                               | name     | NULL",
        // a quoted value cannot forge a parameter boundary: the quoted span is skipped whole, so
        // this field is not mistaken for a file part and dropped
        "form-data; name=\"; filename=x\"   | filename | NULL",
        "form-data; name=\"; filename=x\"   | name     | '; filename=x'",
        // and the same aliasing the other way round does not rename the field
        "form-data; filename=\"; name=y\"   | name     | NULL",
        // RFC 7230 optional whitespace is tolerated on both sides of the "="
        "form-data; name =user             | name     | user",
        "form-data; name= user             | name     | user",
        "form-data; name = \"a b\"          | name     | a b",
        // a bare parameter name is not a parameter
        "form-data; name                    | name     | NULL",
      })
  void readsParameters(String headerValue, String paramName, String expected) {
    assertEquals(expected, parameter(headerValue, paramName));
  }

  @Test
  void unescapesQuotedParameterValues() {
    assertEquals("a\"b", parameter("form-data; name=\"a\\\"b\"", "name"));
  }

  @Test
  void toleratesTabsAroundTheParameterEquals() {
    assertEquals("user", parameter("form-data;\tname\t=\tuser", "name"));
  }

  @Test
  void keepsWhatWasReadOfAnUnterminatedQuotedValue() {
    assertEquals("abc", parameter("form-data; name=\"abc", "name"));
    assertEquals("a\"", parameter("form-data; name=\"a\\\"", "name"));
  }

  @ParameterizedTest(name = "[{index}] {0} -> {2} part(s)")
  @CsvSource(
      delimiter = '|',
      value = {
        "happy path                | --x@A: b@@v@--x--          | 1",
        "two parts                 | --x@@a@--x@@b@--x--        | 2",
        "preamble is discarded     | junk@--x@@v@--x--          | 1",
        "epilogue is discarded     | --x@@v@--x--@junk          | 1",
        // a truncated body still yields its last part
        "truncated last delimiter  | --x@A: b@@v                | 1",
        "truncated in the headers  | --x@A: b                   | 0",
        "no line break at all      | --x                        | 0",
        "close delimiter only      | --x--                      | 0",
        "empty part content        | --x@A: b@@                 | 1",
        "part without headers      | --x@@v@--x--               | 1",
        "unrelated delimiter       | --y@@v@--y--               | 0",
        // RFC 2046 transport padding between the delimiter and its line break
        "padded delimiter          | --x  @@v@--x--             | 1",
      })
  void splitsBodies(String name, String template, int expectedParts) {
    assertEquals(
        expectedParts, split(template.replace("@", "\r\n"), "x", NO_PART_BUDGET_LIMIT).size());
  }

  @Test
  void toleratesBareLineFeeds() {
    String body = "--x\nContent-Disposition: form-data; name=a\n\nvalue\n--x--\n";

    List<Part> parts = split(body, "x", NO_PART_BUDGET_LIMIT);

    assertEquals(1, parts.size());
    assertEquals("value", content(body, parts.get(0)));
  }

  @Test
  void stopsAtThePartBudget() {
    String body = ("--x\r\n\r\na\r\n--x\r\n\r\nb\r\n--x\r\n\r\nc\r\n--x--");

    assertEquals(2, split(body, "x", 2).size());
    assertEquals(0, split(body, "x", 0).size());
  }

  @Test
  void delimitsContentExactly() {
    // Dashes, line breaks, a replacement character and a multi-byte character all inside the
    // content, plus a line that starts with the delimiter without being one: the reported range
    // must not be thrown off by a near miss on the line-feed anchor or on the delimiter itself
    String content = "--not-a-boundary\r\n--x-not-a-boundary\r\n-x\nlast�é";
    String body =
        "--x\r\nContent-Disposition: form-data; name=a\r\n\r\n" + content + "\r\n--x--\r\n";

    List<Part> parts = split(body, "x", NO_PART_BUDGET_LIMIT);

    assertEquals(1, parts.size());
    assertEquals(content, content(body, parts.get(0)));
    assertEquals("form-data; name=a", parts.get(0).contentDisposition);
  }

  @Test
  void confinesAPartWhoseHeadersRunIntoTheNextDelimiter() {
    // The first part's headers are not followed by a blank line. Reading past the delimiter would
    // merge the following part's headers into this one, so a well-formed field part would be
    // reported as the malformed part's own — and skipped entirely if it carried a filename.
    String body =
        "--x\r\nX-First: 1\r\n"
            + "--x\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\nsecond\r\n--x--";

    List<Part> parts = split(body, "x", NO_PART_BUDGET_LIMIT);

    // The surviving part is the second one: had the two merged, its content would have swallowed
    // the delimiter between them.
    assertEquals(1, parts.size());
    assertEquals("form-data; name=\"b\"", parts.get(0).contentDisposition);
    assertEquals("second", content(body, parts.get(0)));
  }

  @Test
  void matchesHeaderNamesCaseInsensitivelyAndTrimsValues() {
    String body =
        "--x\r\nCONTENT-Disposition:  form-data; name=a \r\n"
            + "content-type \t:\ttext/plain \r\n\r\nv\r\n--x--";

    Part part = split(body, "x", NO_PART_BUDGET_LIMIT).get(0);

    assertEquals("form-data; name=a", part.contentDisposition);
    assertEquals("text/plain", part.contentType);
  }

  @Test
  void reportsNoValueForAHeaderThatIsPresentButEmpty() {
    String body = "--x\r\nContent-Type:\r\n\r\nv\r\n--x--";

    Part part = split(body, "x", NO_PART_BUDGET_LIMIT).get(0);

    assertEquals("", part.contentType);
    assertNull(part.contentDisposition);
  }

  @Test
  @Timeout(value = 10, unit = SECONDS)
  void dropsHeadersItDoesNotReadWhateverTheirNumber() {
    // Only Content-Disposition and Content-Type are kept, so a part may declare arbitrarily many
    // others without any of them being retained.
    StringBuilder headers = new StringBuilder();
    for (int i = 0; i < 100_000; i++) {
      headers.append("X-Filler-").append(i).append(": ").append(i).append("\r\n");
    }
    String body = "--x\r\n" + headers + "Content-Disposition: form-data; name=a\r\n\r\nv\r\n--x--";

    List<Part> parts = split(body, "x", NO_PART_BUDGET_LIMIT);

    assertEquals(1, parts.size());
    assertEquals("form-data; name=a", parts.get(0).contentDisposition);
    assertNull(parts.get(0).contentType);
    assertEquals("v", content(body, parts.get(0)));
  }

  @Test
  @Timeout(value = 10, unit = SECONDS)
  void returnsPromptlyOnAnAdversarialBoundary() {
    // Dashes are legal boundary characters, so a scan seeded on '-' would be quadratic here.
    // Anchored on the mandatory line feed, of which this body has none, the scan is linear.
    String boundary = repeat('-', 69) + "X";
    String body = repeat('-', 1_000_000);

    assertTrue(split(body, boundary, NO_PART_BUDGET_LIMIT).isEmpty());
  }

  @Test
  @Timeout(value = 30, unit = SECONDS)
  void neverThrowsOnAMutatedBody() {
    String body =
        "preamble\r\n--x\r\nContent-Disposition: form-data; name=\"a\"\r\n"
            + "Content-Type: application/json\r\n\r\n{\"k\":1}\r\n"
            + "--x\r\nContent-Disposition: form-data; name=b; filename=\"f\"\r\n\r\nfile\r\n"
            + "--x--\r\nepilogue";
    // Fixed positions rather than a random seed, so a failure is reproducible
    char[] substitutes = {'-', '\r', '\n', ':', ';', '"', '\\', '=', '\0', '�'};

    for (int i = 0; i <= body.length(); i++) {
      split(body.substring(0, i), "x", NO_PART_BUDGET_LIMIT);
      for (char substitute : substitutes) {
        if (i < body.length()) {
          split(
              body.substring(0, i) + substitute + body.substring(i + 1), "x", NO_PART_BUDGET_LIMIT);
        }
      }
    }
  }

  private static String content(String body, Part part) {
    return body.substring(part.contentStart, part.contentEnd);
  }

  private static String repeat(char c, int count) {
    StringBuilder builder = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      builder.append(c);
    }
    return builder.toString();
  }
}
