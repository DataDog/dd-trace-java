package datadog.trace.lambda;

import static datadog.trace.lambda.ContentTypeBodyParser.MAX_DEPTH;
import static datadog.trace.lambda.ContentTypeBodyParser.MAX_PARTS;
import static datadog.trace.lambda.ContentTypeBodyParser.dispatch;
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

class ContentTypeBodyParserTest {

  private static final String MULTIPART = "multipart/form-data; boundary=outer";
  private static final String URL_ENCODED = "application/x-www-form-urlencoded";

  @ParameterizedTest(name = "[{index}] {1} -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        // content type absent or blank: best-effort JSON, as before content-type dispatch existed
        "{\"a\":1}          |                                   | MAP",
        "{\"a\":1}          | '   '                             | MAP",
        "{\"a\":1}          | ''                                | MAP",
        "not json           |                                   | STRING",
        // a header holding nothing but parameters declares no type either
        "{\"a\":1}          | '; charset=utf-8'                 | MAP",
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
        // a json-ish subtype wins over the multipart branch, whatever the type says
        "{\"a\":1}          | multipart/json                    | MAP",
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
    Map<String, Object> parsed = urlEncoded("user=admin&role=root");

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
    Map<String, Object> parsed = urlEncoded("na+me=hello+world&q=%7B%22a%22%3A1%7D");

    assertEquals(singletonList("hello world"), parsed.get("na me"));
    assertEquals(singletonList("{\"a\":1}"), parsed.get("q"));
  }

  @Test
  void keepsUndecodableUrlEncodedTokensAsIs() {
    Map<String, Object> parsed = urlEncoded("a=%&%=b");

    assertEquals(singletonList("%"), parsed.get("a"));
    assertEquals(singletonList("b"), parsed.get("%"));
  }

  @Test
  void treatsValuelessUrlEncodedPairsAsEmptyValues() {
    Map<String, Object> parsed = urlEncoded("flag&other=&last");

    assertEquals(singletonList(""), parsed.get("flag"));
    assertEquals(singletonList(""), parsed.get("other"));
    assertEquals(singletonList(""), parsed.get("last"));
  }

  @Test
  void skipsEmptyUrlEncodedPairsAndNames() {
    Map<String, Object> parsed = urlEncoded("&&=orphan&&a=1&&");

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
  void parsesUrlEncodedPairsUpToThePartAllowance() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < MAX_PARTS; i++) {
      body.append(i == 0 ? "" : "&").append('k').append(i).append("=v");
    }

    assertEquals(MAX_PARTS, urlEncoded(body.toString()).size());
  }

  @Test
  void keepsUrlEncodedBodyOverThePartAllowanceAsRawString() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < MAX_PARTS; i++) {
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
    for (int i = 0; i < MAX_PARTS; i++) {
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
  void skipsMultipartPartsWithoutAName() {
    String body = outer(part("form-data", "novalue"), part("form-data; name=", "empty"));

    assertEquals(body, parseBody(body, MULTIPART));
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
    // Only the empty case is worth covering: MultipartSplitter trims header values, so a
    // whitespace-only Content-Type reaches the parser as "" and not as its original spelling
    Map<String, Object> fields = multipart(part("form-data; name=\"amount\"", "", "12345"));

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
    Map<String, Object> fields = multipart(part("form-data; name=\"; filename=x\"", "payload"));

    assertEquals("payload", fields.get("; filename=x"));
  }

  @Test
  void parsesNestedMultipartBodiesAndSkipsTheirFileParts() {
    String inner = body("inner", field("nested", "value"), file("upload", "f.txt", "data"));
    String nesting = outer(part("form-data; name=group", "multipart/mixed; boundary=inner", inner));

    Map<String, Object> fields = asMap(parseBody(nesting, MULTIPART));

    assertEquals(singletonMap("nested", "value"), fields.get("group"));
  }

  @Test
  void sharesThePartAllowanceBetweenUrlEncodedParametersAndMultipartParts() {
    // Two thirds of the allowance each: the first part fits, the second cannot, which only holds if
    // both draw from one allowance rather than from a per-part counter
    String urlEncoded = urlEncodedPairs(MAX_PARTS * 2 / 3);
    Map<String, Object> fields =
        multipart(
            part("form-data; name=first", URL_ENCODED, urlEncoded),
            part("form-data; name=second", URL_ENCODED, urlEncoded));

    assertInstanceOf(Map.class, fields.get("first"));
    assertEquals(urlEncoded, fields.get("second"));
  }

  @Test
  void spendsThePartAllowanceOnlyOnPairsItReads() {
    // Half the allowance each, less the two multipart parts carrying them: together they fit
    // exactly, which they could not if either body were charged upfront
    String urlEncoded = urlEncodedPairs((MAX_PARTS - 2) / 2);
    Map<String, Object> fields =
        multipart(
            part("form-data; name=first", URL_ENCODED, urlEncoded),
            part("form-data; name=second", URL_ENCODED, urlEncoded));

    assertInstanceOf(Map.class, fields.get("first"));
    assertInstanceOf(Map.class, fields.get("second"));
  }

  private static String urlEncodedPairs(int count) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < count; i++) {
      body.append(i == 0 ? "" : "&").append('k').append(i).append("=v");
    }
    return body.toString();
  }

  @Test
  void parsesMultipartPartsUpToThePartAllowance() {
    assertEquals(MAX_PARTS, multipart(fieldParts(MAX_PARTS)).size());
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
        multipart(part("form-data; name=group", "multipart/mixed; boundary=inner", inner));

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
  void keepsABodyTheByteAllowanceCannotCoverAsRawString() {
    String body = outer(field("user", "admin"));

    assertInstanceOf(Map.class, parseBody(body, MULTIPART, new ParseContext(body.length())));
    assertEquals(body, parseBody(body, MULTIPART, new ParseContext(body.length() - 1)));
  }

  @Test
  void chargesTheByteAllowanceWhateverTheContentType() {
    // The allowance bounds reading, not multipart specifically, so a JSON body is charged too
    String body = "{\"a\":1}";

    assertInstanceOf(
        Map.class, parseBody(body, "application/json", new ParseContext(body.length())));
    assertEquals(body, parseBody(body, "application/json", new ParseContext(body.length() - 1)));
  }

  @Test
  void sharesTheByteAllowanceAcrossNestingLevels() {
    // Without a shared allowance a nested body is re-measured against a fresh allowance at every
    // level, so one crafted body costs MAX_DEPTH times its own size to scan and copy
    String inner = body("inner", field("deep", "v"));
    String nested = outer(part("form-data; name=\"n\"", "multipart/mixed; boundary=inner", inner));

    // enough for the outer body alone, one character short of also covering the nested one
    ParseContext exhausted = new ParseContext(nested.length() + inner.length() - 1);
    Map<String, Object> fields = asMap(parseBody(nested, MULTIPART, exhausted));
    assertEquals(inner, fields.get("n"));

    ParseContext sufficient = new ParseContext(nested.length() + inner.length());
    Map<String, Object> parsed = asMap(parseBody(nested, MULTIPART, sufficient));
    assertEquals("v", ((Map<?, ?>) parsed.get("n")).get("deep"));
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
    assertEquals(singletonMap("user", "admin"), multipartBody(body));
  }

  @Test
  void marksAFilePartWithoutReportingAnEmptyFilename() {
    // Browsers send filename="" for an untouched file input: the part is still a file, but the
    // empty name gives a rule nothing to match
    String body = outer(file("avatar", "", "bytes"), field("user", "admin"));

    assertEquals(emptyList(), filenamesOf(body));
    assertEquals(singletonMap("user", "admin"), multipartBody(body));
  }

  @Test
  void reportsFilenamesFromNestedMultipartParts() {
    String inner = body("inner", file("attachment", "nested.txt", "bytes"));
    String nesting = outer(part("form-data; name=group", "multipart/mixed; boundary=inner", inner));

    assertEquals(singletonList("nested.txt"), filenamesOf(nesting));
  }

  @Test
  void reportsFilenamesEvenWhenTheBodyDegradesToARawString() {
    // Nothing but file parts, so there is no field to report. An empty map would tell the WAF the
    // body was empty, so the raw string is kept, and the filenames are then the only structured
    // thing left to hand it.
    String body = outer(file("avatar", "cat.png", "bytes"));

    assertEquals(body, parseBody(body, MULTIPART));
    assertEquals(singletonList("cat.png"), filenamesOf(body));
  }

  @Test
  void reportsNoFilenameWhenTheMultipartBodyIsNotParsed() {
    String body = outer(file("avatar", "cat.png", "bytes"));

    ParseContext sizeCapped = new ParseContext(body.length() - 1);
    assertEquals(body, parseBody(body, MULTIPART, sizeCapped));
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

  private static Object parseBody(String body, String contentType) {
    return parseBody(body, contentType, new ParseContext());
  }

  private static Object parseBody(String body, String contentType, ParseContext context) {
    return ContentTypeBodyParser.parseBody(body, contentType, context);
  }

  /** Wraps the parts in a body with the default boundary and parses it. */
  private static Map<String, Object> multipart(String... parts) {
    return multipartBody(outer(parts));
  }

  /** Distinct from {@link #multipart}, whose varargs would otherwise swallow a whole body. */
  private static Map<String, Object> multipartBody(String body) {
    return asMap(parseBody(body, MULTIPART));
  }

  private static Map<String, Object> urlEncoded(String body) {
    return asMap(parseBody(body, URL_ENCODED));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object parsed) {
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
}
