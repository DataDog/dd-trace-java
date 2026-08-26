package datadog.trace.lambda;

import static datadog.trace.lambda.ContentTypeBodyParser.MAX_DEPTH;
import static datadog.trace.lambda.ContentTypeBodyParser.MAX_ELEMENTS;
import static datadog.trace.lambda.ContentTypeBodyParser.parseBody;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ContentTypeBodyParserTest {

  @ParameterizedTest(name = "[{index}] {1} -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        // content type absent or blank: best-effort JSON, as before content-type dispatch existed
        "{\"a\":1}          |                                   | MAP",
        "{\"a\":1}          | '   '                             | MAP",
        "not json           |                                   | STRING",
        // JSON, including suffixed subtypes and parameters
        "{\"a\":1}          | application/json                  | MAP",
        "{\"a\":1}          | application/json; charset=utf-8   | MAP",
        "{\"a\":1}          | APPLICATION/JSON                  | MAP",
        "{\"a\":1}          | application/vnd.api+json          | MAP",
        "{\"a\":1           | application/json                  | STRING",
        // JSON-ish vendor types: no +json suffix, but the payload is JSON
        "{\"a\":1}          | application/x-amz-json-1.1        | MAP",
        "{\"a\":1}          | application/javascript            | MAP",
        // urlencoded
        "a=1                | application/x-www-form-urlencoded | MAP",
        "a=1                | APPLICATION/X-WWW-FORM-URLENCODED | MAP",
        // multipart is not structured yet
        "{\"a\":1}          | multipart/form-data; boundary=xy  | STRING",
        // text/*: never structured, even when it holds JSON. A JSON parse of "12345" would yield a
        // Double, which no string rule can match
        "{\"a\":1}          | text/plain                        | STRING",
        "12345              | text/plain                        | STRING",
        "{\"a\":1}          | text/html                         | STRING",
        // anything else
        "{\"a\":1}          | application/xml                   | STRING",
        "{\"a\":1}          | application/octet-stream          | STRING",
        "{\"a\":1}          | garbage                           | STRING",
        // known gap: a JSON-carrying type whose name says neither "json" nor "javascript" keeps
        // the raw string, which the WAF can still match string rules against
        "{\"a\":1}          | application/csp-report            | STRING",
      })
  void dispatchesOnContentType(String body, String contentType, String expectedKind) {
    Object parsed = parseBody(body, contentType);

    if ("MAP".equals(expectedKind)) {
      assertInstanceOf(Map.class, parsed);
    } else {
      assertEquals(body, parsed);
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void parsesJsonWhenContentTypeIsAbsent(String contentType) {
    Object parsed = parseBody("{\"user\":\"admin\"}", contentType);

    assertInstanceOf(Map.class, parsed);
    assertEquals("admin", ((Map<?, ?>) parsed).get("user"));
  }

  @Test
  void returnsNullForNullBody() {
    assertNull(parseBody(null, "application/json"));
  }

  @Test
  void keepsEmptyBodyAsEmptyString() {
    assertEquals("", parseBody("", "application/json"));
    assertEquals("", parseBody("", "application/x-www-form-urlencoded"));
  }

  @Test
  void keepsRawStringOnceMaxDepthIsReached() {
    String body = "{\"a\":1}";

    assertEquals(body, ContentTypeBodyParser.dispatch(body, "application/json", MAX_DEPTH));
    assertInstanceOf(
        Map.class, ContentTypeBodyParser.dispatch(body, "application/json", MAX_DEPTH - 1));
  }

  @Test
  void parsesUrlEncodedIntoAMultimap() {
    Map<String, List<String>> parsed = urlEncoded("user=admin&role=root");

    assertEquals(singletonList("admin"), parsed.get("user"));
    assertEquals(singletonList("root"), parsed.get("role"));
    assertEquals(2, parsed.size());
  }

  @Test
  void groupsRepeatedUrlEncodedKeysIntoOneList() {
    assertEquals(asList("a", "b", "c"), urlEncoded("x=a&x=b&x=c").get("x"));
  }

  @Test
  void decodesUrlEncodedPercentEscapesAndPluses() {
    Map<String, List<String>> parsed = urlEncoded("na+me=hello+world&q=%7B%22a%22%3A1%7D");

    assertEquals(singletonList("hello world"), parsed.get("na me"));
    assertEquals(singletonList("{\"a\":1}"), parsed.get("q"));
  }

  @Test
  void keepsUndecodableUrlEncodedTokensAsIs() {
    Map<String, List<String>> parsed = urlEncoded("a=%&%=b");

    assertEquals(singletonList("%"), parsed.get("a"));
    assertEquals(singletonList("b"), parsed.get("%"));
  }

  @Test
  void treatsValuelessUrlEncodedPairsAsEmptyValues() {
    Map<String, List<String>> parsed = urlEncoded("flag&other=&last");

    assertEquals(singletonList(""), parsed.get("flag"));
    assertEquals(singletonList(""), parsed.get("other"));
    assertEquals(singletonList(""), parsed.get("last"));
  }

  @Test
  void skipsEmptyUrlEncodedPairsAndNames() {
    Map<String, List<String>> parsed = urlEncoded("&&=orphan&&a=1&&");

    assertEquals(singletonList("1"), parsed.get("a"));
    assertEquals(1, parsed.size());
  }

  @Test
  void splitsUrlEncodedValuesAtTheFirstEqualsOnly() {
    assertEquals(singletonList("b=c=d"), urlEncoded("a=b=c=d").get("a"));
  }

  @Test
  void doesNotSeparateUrlEncodedPairsOnSemicolons() {
    assertEquals(singletonList("1;b=2"), urlEncoded("a=1;b=2").get("a"));
  }

  @Test
  void capsUrlEncodedPairsAtMaxElements() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < MAX_ELEMENTS + 1; i++) {
      body.append(i == 0 ? "" : "&").append('k').append(i).append("=v");
    }

    assertEquals(MAX_ELEMENTS, urlEncoded(body.toString()).size());
  }

  @Test
  void stopsScanningNamelessUrlEncodedPairsAtTheCap() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < MAX_ELEMENTS; i++) {
      body.append("=v&");
    }
    body.append("a=1");
    String raw = body.toString();

    // Nameless pairs still do decoding work, so they exhaust the cap and the trailing parameter is
    // never reached; nothing survives, so the raw body is kept
    assertEquals(raw, parseBody(raw, "application/x-www-form-urlencoded"));
  }

  @Test
  void keepsUnparseableUrlEncodedBodyAsRawString() {
    // Nothing but separators: no parameter survives, so the raw body is kept
    assertEquals("&&&", parseBody("&&&", "application/x-www-form-urlencoded"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> urlEncoded(String body) {
    Object parsed = parseBody(body, "application/x-www-form-urlencoded");
    assertInstanceOf(Map.class, parsed);
    return (Map<String, List<String>>) parsed;
  }
}
