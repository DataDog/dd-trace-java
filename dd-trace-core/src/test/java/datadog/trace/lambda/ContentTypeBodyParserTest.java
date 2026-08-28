package datadog.trace.lambda;

import static datadog.trace.lambda.ContentTypeBodyParser.MAX_DEPTH;
import static datadog.trace.lambda.ContentTypeBodyParser.MAX_ELEMENTS;
import static datadog.trace.lambda.ContentTypeBodyParser.MAX_PARTS;
import static datadog.trace.lambda.ContentTypeBodyParser.dispatch;
import static datadog.trace.lambda.ContentTypeBodyParser.parseBody;
import static datadog.trace.lambda.ContentTypeBodyParser.parseMultipart;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.lambda.ContentTypeBodyParser.ParseContext;
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
        // a multipart body whose boundary happens to contain "json" must still reach the multipart
        // parser rather than being handed to the JSON parser
        "not multipart      | multipart/x; boundary=--json      | STRING",
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

    assertEquals(body, dispatch(body, "application/json", MAX_DEPTH, new ParseContext()));
    assertInstanceOf(
        Map.class, dispatch(body, "application/json", MAX_DEPTH - 1, new ParseContext()));
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
  void parsesUrlEncodedPairsUpToMaxElements() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < MAX_ELEMENTS; i++) {
      body.append(i == 0 ? "" : "&").append('k').append(i).append("=v");
    }

    assertEquals(MAX_ELEMENTS, urlEncoded(body.toString()).size());
  }

  @Test
  void keepsUrlEncodedBodyOverMaxElementsAsRawString() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < MAX_ELEMENTS; i++) {
      body.append('k').append(i).append("=v&");
    }
    body.append("attack=payload");
    String raw = body.toString();

    // Truncating would hide the trailing parameter from every WAF rule, so the whole body is kept
    // as a string instead — still matchable, just unstructured
    assertEquals(raw, parseBody(raw, "application/x-www-form-urlencoded"));
  }

  @Test
  void countsNamelessUrlEncodedPairsTowardsTheCap() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < MAX_ELEMENTS; i++) {
      body.append("=v&");
    }
    body.append("a=1");
    String raw = body.toString();

    // Nameless pairs still do decoding work, so they count towards the cap even though none of them
    // ends up in the map
    assertEquals(raw, parseBody(raw, "application/x-www-form-urlencoded"));
  }

  @Test
  void keepsUnparseableUrlEncodedBodyAsRawString() {
    // Nothing but separators: no parameter survives, so the raw body is kept
    assertEquals("&&&", parseBody("&&&", "application/x-www-form-urlencoded"));
  }

  @Test
  void parsesMultipartFieldsIntoAMap() {
    Map<String, Object> fields = multipart(field("user", "admin"), field("role", "root"));

    assertEquals("admin", fields.get("user"));
    assertEquals("root", fields.get("role"));
    assertEquals(2, fields.size());
  }

  @Test
  void skipsMultipartFileParts() {
    Map<String, Object> fields = multipart(field("user", "admin"), file("upload", "f.txt", "data"));

    assertEquals("admin", fields.get("user"));
    assertEquals(1, fields.size());
  }

  @Test
  void skipsMultipartPartsWithAnEmptyFilename() {
    // An untouched file input: browsers send filename="", which is a file part all the same
    Map<String, Object> fields = multipart(field("user", "admin"), file("upload", "", ""));

    assertEquals(1, fields.size());
  }

  @Test
  void keepsAFileOnlyMultipartBodyAsARawString() {
    // A raw string still matches string rules; an empty map would tell the WAF the body was empty
    String body = outer(file("upload", "f.txt", "data"));

    assertEquals(body, parseBody(body, MULTIPART));
  }

  @Test
  void skipsMultipartPartsWithoutAName() {
    assertEquals(
        outer(part("form-data", "novalue"), part("form-data; name=", "empty")),
        parseBody(
            outer(part("form-data", "novalue"), part("form-data; name=", "empty")), MULTIPART));
  }

  @Test
  void skipsMultipartPartsWithoutAContentDisposition() {
    Map<String, Object> fields = multipart(field("user", "admin"), part(null, "orphan"));

    assertEquals(1, fields.size());
  }

  @Test
  void dispatchesOnEachMultipartPartsOwnContentType() {
    Map<String, Object> fields =
        multipart(
            part("form-data; name=payload", "application/json", "{\"a\":1}"),
            part("form-data; name=plain", "text/plain", "12345"),
            part("form-data; name=form", "application/x-www-form-urlencoded", "k=v"));

    assertInstanceOf(Map.class, fields.get("payload"));
    assertEquals("12345", fields.get("plain"));
    assertEquals(singletonList("v"), ((Map<?, ?>) fields.get("form")).get("k"));
  }

  @Test
  void keepsMultipartPartsThatDeclareNoContentTypeAsRawStrings() {
    // A part with no Content-Type is text/plain per RFC 7578, section 4.4, not a body of unknown
    // type: a JSON parse would hand the WAF a Double, a Boolean and a Map, so the same form
    // submitted urlencoded and as multipart would no longer match the same string rules
    Map<String, Object> fields =
        multipart(field("amount", "12345"), field("flag", "true"), field("json", "{\"a\":1}"));

    assertEquals("12345", fields.get("amount"));
    assertEquals("true", fields.get("flag"));
    assertEquals("{\"a\":1}", fields.get("json"));
  }

  @Test
  void keepsMultipartPartsWithAnEmptyContentTypeAsRawStrings() {
    Map<String, Object> fields = multipart(outer(part("form-data; name=\"amount\"", "", "12345")));

    assertEquals("12345", fields.get("amount"));
  }

  @Test
  void promotesRepeatedMultipartFieldNamesToAList() {
    Map<String, Object> fields = multipart(field("x", "a"), field("x", "b"), field("x", "c"));

    assertEquals(asList("a", "b", "c"), fields.get("x"));
  }

  @Test
  void nestsARepeatedJsonArrayPartValueRatherThanFlatteningIt() {
    // The first value is itself a List, so a repeat cannot be told from a fresh key by the stored
    // value's type: appending to it would hand the WAF a structure that was never sent
    Map<String, Object> fields =
        multipart(part("form-data; name=x", "application/json", "[1,2]"), field("x", "b"));

    assertEquals(asList(asList(1.0, 2.0), "b"), fields.get("x"));
  }

  @Test
  void doesNotTakeAFieldNameThatForgesAFilenameForAFilePart() {
    Map<String, Object> fields =
        multipart(outer(part("form-data; name=\"; filename=x\"", "payload")));

    assertEquals("payload", fields.get("; filename=x"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void parsesNestedMultipartBodiesAndSkipsTheirFileParts() {
    String inner = body("inner", field("nested", "value"), file("upload", "f.txt", "data"));
    String nesting = outer(part("form-data; name=group", "multipart/mixed; boundary=inner", inner));

    Map<String, Object> fields = (Map<String, Object>) parseBody(nesting, MULTIPART);

    assertEquals(singletonMap("nested", "value"), fields.get("group"));
  }

  @Test
  void sharesTheElementAllowanceAcrossNestingLevels() {
    // Two thirds of the allowance each: the first part fits, the second cannot, which only holds if
    // both draw from one allowance rather than from a per-part counter
    String urlEncoded = urlEncodedPairs(MAX_ELEMENTS * 2 / 3);
    Map<String, Object> fields =
        multipart(
            outer(
                part("form-data; name=first", URL_ENCODED, urlEncoded),
                part("form-data; name=second", URL_ENCODED, urlEncoded)));

    assertInstanceOf(Map.class, fields.get("first"));
    assertEquals(urlEncoded, fields.get("second"));
  }

  @Test
  void spendsTheElementAllowanceOnlyOnPairsItReads() {
    // Just under the allowance twice over would exceed it if the whole body were charged upfront
    String urlEncoded = urlEncodedPairs(MAX_ELEMENTS / 2);
    Map<String, Object> fields =
        multipart(
            outer(
                part("form-data; name=first", URL_ENCODED, urlEncoded),
                part("form-data; name=second", URL_ENCODED, urlEncoded)));

    assertInstanceOf(Map.class, fields.get("first"));
    assertInstanceOf(Map.class, fields.get("second"));
  }

  private static final String URL_ENCODED = "application/x-www-form-urlencoded";

  private static String urlEncodedPairs(int count) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < count; i++) {
      body.append(i == 0 ? "" : "&").append('k').append(i).append("=v");
    }
    return body.toString();
  }

  @Test
  void parsesMultipartPartsUpToThePartAllowance() {
    assertEquals(MAX_PARTS, multipart(outer(fieldParts(MAX_PARTS))).size());
  }

  @Test
  void keepsMultipartBodyOverThePartAllowanceAsRawString() {
    // One part over the allowance drops that part from every WAF address, so the whole body
    // degrades to a raw string instead, as an over-allowance urlencoded body does
    String body = outer(fieldParts(MAX_PARTS + 1));

    assertEquals(body, parseBody(body, MULTIPART));
  }

  @Test
  void sharesThePartAllowanceAcrossNestingLevels() {
    // The inner body alone is within the allowance, but the outer part it sits in has already
    // spent one, so it can no longer be read and degrades to a raw string on its own
    String inner = body("inner", fieldParts(MAX_PARTS));
    Map<String, Object> fields =
        multipart(outer(part("form-data; name=group", "multipart/mixed; boundary=inner", inner)));

    assertEquals(inner, fields.get("group"));
  }

  private static String[] fieldParts(int count) {
    String[] parts = new String[count];
    for (int i = 0; i < count; i++) {
      parts[i] = field("k" + i, "v");
    }
    return parts;
  }

  @Test
  void keepsRawStringOnceMaxDepthIsReachedInAMultipartPart() {
    String body = outer(field("user", "admin"));

    assertEquals(body, dispatch(body, MULTIPART, MAX_DEPTH, new ParseContext()));
  }

  @Test
  void keepsMultipartBodyAsRawStringWithoutAUsableBoundary() {
    String body = outer(field("user", "admin"));

    assertEquals(body, parseBody(body, "multipart/form-data"));
    assertEquals(body, parseBody(body, "multipart/form-data; boundary="));
  }

  @Test
  void keepsUnsplittableMultipartBodyAsRawString() {
    assertEquals("no parts here", parseBody("no parts here", MULTIPART));
  }

  @Test
  void keepsMultipartBodyAboveTheSizeLimitAsRawString() {
    String body = outer(field("user", "admin"));

    assertInstanceOf(
        Map.class, parseMultipart(body, MULTIPART, 0, new ParseContext(), body.length()));
    assertNull(parseMultipart(body, MULTIPART, 0, new ParseContext(), body.length() - 1));
  }

  @Test
  void disablesMultipartParsingWhenTheSizeLimitIsZero() {
    String body = outer(field("user", "admin"));

    assertNull(parseMultipart(body, MULTIPART, 0, new ParseContext(), 0));
  }

  @Test
  void reportsTheFilenamesOfFileParts() {
    String body =
        outer(
            field("user", "admin"),
            file("avatar", "cat.png", "bytes"),
            file("doc", "report.pdf", "bytes"));

    assertEquals(asList("cat.png", "report.pdf"), filenamesOf(body));
    // The file parts are not fields, so only the field survives into the body map
    assertEquals(singletonMap("user", "admin"), multipart(body));
  }

  @Test
  void marksAFilePartWithoutReportingAnEmptyFilename() {
    // Browsers send filename="" for an untouched file input: the part is still a file, but the
    // empty name gives a rule nothing to match
    String body = outer(file("avatar", "", "bytes"), field("user", "admin"));

    assertEquals(emptyList(), filenamesOf(body));
    assertEquals(singletonMap("user", "admin"), multipart(body));
  }

  @Test
  void reportsFilenamesFromNestedMultipartParts() {
    String inner = body("inner", file("attachment", "nested.txt", "bytes"));
    String nesting = outer(part("form-data; name=group", "multipart/mixed; boundary=inner", inner));

    assertEquals(singletonList("nested.txt"), filenamesOf(nesting));
  }

  @Test
  void reportsFilenamesEvenWhenTheBodyDegradesToARawString() {
    // Nothing but file parts, so there is no field to report and the body stays a raw string.
    // The filenames are then the only structured thing left to hand the WAF.
    String body = outer(file("avatar", "cat.png", "bytes"));

    assertEquals(body, parseBody(body, MULTIPART));
    assertEquals(singletonList("cat.png"), filenamesOf(body));
  }

  @Test
  void reportsNoFilenameWhenTheMultipartBodyIsNotParsed() {
    String body = outer(file("avatar", "cat.png", "bytes"));

    ParseContext sizeCapped = new ParseContext();
    assertNull(parseMultipart(body, MULTIPART, 0, sizeCapped, 0));
    assertEquals(emptyList(), sizeCapped.filenames());

    ParseContext noBoundary = new ParseContext();
    assertEquals(body, parseBody(body, "multipart/form-data", noBoundary));
    assertEquals(emptyList(), noBoundary.filenames());
  }

  private static List<String> filenamesOf(String body) {
    ParseContext context = new ParseContext();
    parseBody(body, MULTIPART, context);
    return context.filenames();
  }

  private static final String MULTIPART = "multipart/form-data; boundary=outer";

  @SuppressWarnings("unchecked")
  private static Map<String, Object> multipart(String... parts) {
    return multipart(outer(parts));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> multipart(String body) {
    Object parsed = parseBody(body, MULTIPART);
    assertInstanceOf(Map.class, parsed);
    return (Map<String, Object>) parsed;
  }

  /** Joins the parts with CRLF and appends the close delimiter, using the default boundary. */
  private static String outer(String... parts) {
    return body("outer", parts);
  }

  private static String body(String boundary, String... parts) {
    StringBuilder body = new StringBuilder();
    for (String part : parts) {
      body.append("--").append(boundary).append("\r\n").append(part).append("\r\n");
    }
    return body.append("--").append(boundary).append("--\r\n").toString();
  }

  private static String field(String name, String value) {
    return part("form-data; name=\"" + name + "\"", value);
  }

  private static String file(String name, String filename, String value) {
    return part("form-data; name=\"" + name + "\"; filename=\"" + filename + "\"", value);
  }

  private static String part(String disposition, String value) {
    return part(disposition, null, value);
  }

  private static String part(String disposition, String contentType, String value) {
    StringBuilder part = new StringBuilder();
    if (disposition != null) {
      part.append("Content-Disposition: ").append(disposition).append("\r\n");
    }
    if (contentType != null) {
      part.append("Content-Type: ").append(contentType).append("\r\n");
    }
    return part.append("\r\n").append(value).toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> urlEncoded(String body) {
    Object parsed = parseBody(body, "application/x-www-form-urlencoded");
    assertInstanceOf(Map.class, parsed);
    return (Map<String, List<String>>) parsed;
  }
}
