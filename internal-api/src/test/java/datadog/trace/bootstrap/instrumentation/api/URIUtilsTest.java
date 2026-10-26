package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import datadog.trace.bootstrap.instrumentation.api.URIUtils.LazyUrl;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class URIUtilsTest {

  @TableTest({
    "Scenario          | Raw query                           | Expected query                     ",
    "No query          |                                     |                                    ",
    "Empty query       | ''                                  | ''                                 ",
    "Plain query       | a=1&b=2                             | a=1&b=2                            ",
    "Encoded name      | t%6fken=secret                      | token=secret                       ",
    "Encoded ampersand | token=first%26SECOND_HALF&view=full | token=first%26SECOND_HALF&view=full",
    "Encoded fragment  | q=one%23two                         | q=one%23two                        ",
    "Mixed delimiters  | a=1&b=x%26y%23z&c=3                 | a=1&b=x%26y%23z&c=3                ",
    "Double encoding   | q=%2526%2523                        | q=%26%23                           ",
    "Bracketed escapes | q=[a%26b%23c]&view=full             | q=[a%26b%23c]&view=full            ",
    "Mixed brackets    | q=%5B[a%26b]&token=x%26y            | q=[[a%26b]&token=x%26y             ",
    "Unclosed bracket  | q=[a%26b&view=full                  | q=[a%26b&view=full                 ",
    "Nested brackets   | q=[[a%26b]]&r=[[c%23d]]             | q=[[a%26b]]&r=[[c%23d]]            ",
    "Unicode           | q=é%26%C3%A9                        | q=é%26é                            ",
    "Spaces            | q=a%20b                             | 'q=a b'                            "
  })
  void preservesEncodedQueryDelimiters(String rawQuery, String expectedQuery) {
    URI uri = URI.create("http://host/" + (rawQuery == null ? "" : "?" + rawQuery));

    assertEquals(
        expectedQuery, URIUtils.preserveQueryDelimiters(uri.getRawQuery(), uri.getQuery()));
  }

  @TableTest({
    "Scenario            | Raw query | Decoded query | Expected query",
    "Raw unavailable     |           | q=a&b         | q=a&b         ",
    "Decoded unavailable | q=a%26b   |               |               ",
    "Inconsistent query  | q=a%26b   | q=ab          |               ",
    "Plus as space       | q=a+b%26c | 'q=a b&c'     | 'q=a b%26c'   ",
    "Malformed escape    | q=%GG%26b | q=�&b         | q=�%26b       "
  })
  void preservesDelimitersWithAlternativeDecoders(
      String rawQuery, String decodedQuery, String expectedQuery) {
    assertEquals(expectedQuery, URIUtils.preserveQueryDelimiters(rawQuery, decodedQuery));
  }

  @TableTest({
    "Scenario | Suffix",
    "Closed   | '%20]'",
    "Unclosed | '%20' "
  })
  void preservesDelimitersInBracketHeavyQueries(String suffix) {
    char[] brackets = new char[1_000_000];
    Arrays.fill(brackets, '[');
    String bracketedQuery = "q=" + new String(brackets) + suffix;
    String rawQuery = bracketedQuery + "&token=a%26b";
    String expectedQuery = rawQuery.replace("%20", " ");
    String decodedQuery = expectedQuery.replace("%26", "&");

    assertTimeout(
        Duration.ofSeconds(5),
        () -> {
          // Java 8 leaves bracketed escapes intact; newer decoders decode them.
          assertEquals(
              bracketedQuery, URIUtils.preserveQueryDelimiters(bracketedQuery, bracketedQuery));
          assertEquals(expectedQuery, URIUtils.preserveQueryDelimiters(rawQuery, decodedQuery));
        });
  }

  @TableTest({
    "scenario             | input                         | expected              ",
    "port zero            | 'https://host:0'              | 'https://host/'       ",
    "root with query      | 'http://host/?query'          | 'http://host/'        ",
    "path                 | 'https://host/path'           | 'https://host/path'   ",
    "custom port          | 'http://host:47/path?query'   | 'http://host:47/path' ",
    "http default port    | 'http://host:80/path?query'   | 'http://host/path'    ",
    "http with https port | 'http://host:443/path?query'  | 'http://host:443/path'",
    "https default port   | 'https://host:443/path?query' | 'https://host/path'   ",
    "https with http port | 'https://host:80/path?query'  | 'https://host:80/path'"
  })
  void shouldBuildUrls(String input, String expected) throws URISyntaxException {
    URI uri = new URI(input);
    String url = URIUtils.buildURL(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
    LazyUrl lazyUrl =
        URIUtils.lazyValidURL(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());

    assertEquals(expected, url);
    assertEquals(uri.getPath(), lazyUrl.path());
    assertEquals(expected, lazyUrl.get());
    assertEquals(expected, lazyUrl.toString());
  }

  @TableTest({
    "scenario                 | scheme | host | port | path      | expected      ",
    "all null                 |        |      | -1   |           | /             ",
    "all empty                | ''     | ''   | -1   | ''        | ':///'        ",
    "empty with relative path | ''     | ''   | -1   | relative  | ':///relative'",
    "empty with absolute path | ''     | ''   | -1   | /absolute | ':///absolute'",
    "null with relative path  |        |      | -1   | relative  | relative      ",
    "null with absolute path  |        |      | -1   | /absolute | /absolute     "
  })
  void shouldBuildUrlsFromCornerCases(
      String scheme, String host, int port, String path, String expected) {
    String url = URIUtils.buildURL(scheme, host, port, path);
    LazyUrl lazyUrl = URIUtils.lazyValidURL(scheme, host, port, path);

    assertEquals(expected, url);
    assertEquals(path != null ? path : "", lazyUrl.path());
    assertEquals(expected, lazyUrl.toString());
    assertEquals(expected, lazyUrl.get());
  }

  @TableTest({
    "scenario                                  | encoded                             | expected                      ",
    "null                                      |                                     |                               ",
    "empty                                     | ''                                  | ''                            ",
    "plain                                     | plain                               | plain                         ",
    "encoded spaces                            | '%C3%BEungur%20hn%C3%ADfur'         | 'þungur hnífur'               ",
    "plus kept                                 | 'tr%C3%A5kig%20str%C3%A4ng+med+%2B' | 'tråkig sträng+med++'         ",
    "only plus                                 | 'boring+string+with+only+plus'      | 'boring+string+with+only+plus'",
    "too few characters                        | '%'                                 | '�'                           ",
    "too few characters after one digit        | '%1'                                | '�'                           ",
    "illegal 1st character                     | '%W1'                               | '�'                           ",
    "illegal 2nd character                     | '%1X'                               | '�'                           ",
    "illegal 1st character in word             | 'wh%Y1t'                            | 'wh�t'                        ",
    "illegal 2nd character in word             | 'wh%1Zt'                            | 'wh�t'                        ",
    "too few characters at end                 | 'wh%'                               | 'wh�'                         ",
    "too few characters after one digit at end | 'wh%1'                              | 'wh�'                         ",
    "invalid 2 byte sequence                   | '%C3%28'                            | '�('                          ",
    "invalid sequence identifier               | '%A0%A1'                            | '��'                          ",
    "invalid 3 byte sequence in 2nd byte       | '%E2%28%A1'                         | '�(�'                         ",
    "invalid 3 byte sequence in 3rd byte       | '%E2%82%28'                         | '�('                          ",
    "invalid 4 byte sequence in 2nd byte       | '%F0%28%8C%BC'                      | '�(��'                        ",
    "invalid 4 byte sequence in 3rd byte       | '%F0%90%28%BC'                      | '�(�'                         ",
    "invalid 4 byte sequence in 4th byte       | '%F0%28%8C%28'                      | '�(�('                        ",
    "valid 5 byte sequence but not unicode     | '%F8%A1%A1%A1%A1'                   | '�����'                       ",
    "valid 6 byte sequence but not unicode     | '%FC%A1%A1%A1%A1%A1'                | '������'                      "
  })
  void shouldDecodeUrlEncodedIgnoringPlus(String encoded, String expected) {
    String decoded = URIUtils.decode(encoded);

    assertEquals(expected, decoded);
  }

  @TableTest({
    "scenario       | encoded                                          | expected                           ",
    "null           |                                                  |                                    ",
    "plain          | plain                                            | plain                              ",
    "encoded spaces | '%C3%BEungur%20hn%C3%ADfur'                      | 'þungur hnífur'                    ",
    "plus and %2B   | 'v%C3%A4ldigt+tr%C3%A5kig%20str%C3%A4ng+med+%2B' | 'väldigt tråkig sträng med +'      ",
    "only plus      | 'very+boring+string+with+only+plus'              | 'very boring string with only plus'"
  })
  void shouldDecodeUrlEncodedWithPlus(String encoded, String expected) {
    String decoded = URIUtils.decode(encoded, true);

    assertEquals(expected, decoded);
  }

  @Test
  void testLazyUrlForCodeCoverage() {
    String raw = "weird";
    LazyUrl invalid = URIUtils.lazyInvalidUrl(raw);

    assertNull(invalid.path());
    assertEquals(raw, invalid.toString());
    assertEquals(raw, invalid.get());
    assertEquals(raw.length(), invalid.length());
    assertEquals(raw.hashCode(), invalid.hashCode());
    assertEquals("ei", invalid.subSequence(1, 3).toString());
    assertEquals('r', invalid.charAt(3));
  }

  @TableTest({
    "scenario        | first               | second                     | expected                  ",
    "trailing slash  | 'http://localhost/' | 'test/me'                  | 'http://localhost/test/me'",
    "leading slash   | 'http://localhost'  | '/test/me'                 | 'http://localhost/test/me'",
    "absolute second | 'http://localhost/' | 'http://localhost/test/me' | 'http://localhost/test/me'",
    "both null       |                     |                            |                           ",
    "null first      |                     | '/test'                    | '/test'                   ",
    "null second     | 'http://localhost'  |                            | 'http://localhost/'       "
  })
  void testSafeConcatForCodeCoverage(String first, String second, String expected) {
    URI concat = URIUtils.safeConcat(first, second);

    assertEquals(expected, concat == null ? null : concat.toString());
  }
}
