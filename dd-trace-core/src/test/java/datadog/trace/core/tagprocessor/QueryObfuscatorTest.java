package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.re2j.Pattern;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class QueryObfuscatorTest extends DDJavaSpecification {

  // Default pattern extended with 'email' (the 'email' row fails if the replace stops applying)
  private static final String CUSTOM_OBFUSCATION_PATTERN =
      QueryObfuscator.DEFAULT_OBFUSCATION_PATTERN.replace(
          "|pass(?:[-_]?phrase)?|secret", "|pass(?:[-_]?phrase)?|email|secret");

  private static final String JWT =
      "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

  @TableTest({
    "Scenario           | URL                       | Expected URL         ",
    "Decoded hash       | http://host/a%23b?q=1     | http://host/a#b?q=1  ",
    "Decoded question   | http://host/a%3Fb?q=1     | http://host/a?b?q=1  ",
    "Decoded delimiters | http://host/a%3Fb%23c?q=1 | http://host/a?b#c?q=1"
  })
  void datadogQueryReconstructionPreservesDecodedPath(String url, String expectedUrl) {
    URI uri = URI.create(url);
    TagMap tags = TagMap.create();
    tags.put(
        Tags.HTTP_URL,
        URIUtils.lazyValidURL(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath()));
    tags.put(DDTags.HTTP_QUERY, uri.getQuery());

    new QueryObfuscator(null, false).processTags(tags, null, link -> {});

    assertEquals("q=1", tags.get(DDTags.HTTP_QUERY));
    assertEquals(expectedUrl, tags.get(Tags.HTTP_URL));
  }

  @TableTest({
    "Scenario           | URL                                                    | Query               | Expected URL                                          ",
    "OpenTelemetry      | https://example.com/orders?page=1                      | page=1              | https://example.com/orders?page=1                     ",
    "OTel redaction     | https://example.com/orders?token=secret&page=1#details | token=secret&page=1 | 'https://example.com/orders?<redacted>&page=1#details'",
    "OTel fragment only | https://example.com/orders#details?view=full           | page=1              | https://example.com/orders?page=1#details?view=full   "
  })
  void existingOtelUrlQueryIsReplaced(String url, String query, String expectedUrl) {
    TagMap tags = TagMap.create();
    tags.put(Tags.HTTP_URL, url);
    tags.put("url.query", query);

    new QueryObfuscator(null, true).processTags(tags, null, link -> {});

    assertEquals(expectedUrl, tags.get(Tags.HTTP_URL));
    assertEquals(query.replace("token=secret", "<redacted>"), tags.get("url.query"));
    assertFalse(tags.containsKey(DDTags.HTTP_QUERY));
  }

  @Test
  void datadogProcessingKeepsQueryTagsSeparate() {
    TagMap tags = TagMap.create();
    tags.put(Tags.HTTP_URL, "https://example.com/orders?page=1");
    tags.put("url.query", "page=1");

    QueryObfuscator obfuscator = new QueryObfuscator(null, false);
    obfuscator.processTags(tags, null, link -> {});

    assertEquals("https://example.com/orders?page=1", tags.get(Tags.HTTP_URL));
    assertEquals("page=1", tags.get("url.query"));
    assertFalse(tags.containsKey(DDTags.HTTP_QUERY));

    tags.put(DDTags.HTTP_QUERY, "token=secret");
    obfuscator.processTags(tags, null, link -> {});

    assertEquals("https://example.com/orders?page=1?<redacted>", tags.get(Tags.HTTP_URL));
    assertEquals("<redacted>", tags.get(DDTags.HTTP_QUERY));
    assertEquals("page=1", tags.get("url.query"));
  }

  @TableTest({
    "Scenario           | Raw query                                   | Regex         | Expected query                     ",
    "Encoded ampersand  | token=first%26SECOND_HALF&view=full         |               | <redacted>&view=full               ",
    "Encoded name       | t%6fken=first%26SECOND_HALF&view=full       |               | <redacted>&view=full               ",
    "Encoded fragment   | token=first%23SECOND_HALF&view=full         |               | <redacted>&view=full               ",
    "Safe delimiters    | q=one%26two%23three&view=full               |               | q=one%26two%23three&view=full      ",
    "Encoded JSON       | data=%7B%22token%22%3A%22first%26LAST%22%7D |               | 'data={<redacted>}'                ",
    "Custom regex       | private=first%26SECOND_HALF&view=full       | private=[^&]+ | <redacted>&view=full               ",
    "Disabled redaction | token=first%26SECOND_HALF&view=full         | ''            | token=first%26SECOND_HALF&view=full"
  })
  void decodedQueryRedactionPreservesEncodedBoundaries(
      String rawQuery, String regex, String expectedQuery) {
    URI uri = URI.create("http://site.com/orders?" + rawQuery);
    TagMap tags = TagMap.create();
    tags.put(Tags.HTTP_URL, "http://site.com/orders");
    tags.put(
        DDTags.HTTP_QUERY, URIUtils.preserveQueryDelimiters(uri.getRawQuery(), uri.getQuery()));

    new QueryObfuscator(regex).processTags(tags, null, link -> {});

    assertEquals(expectedQuery, tags.get(DDTags.HTTP_QUERY));
    assertEquals("http://site.com/orders?" + expectedQuery, tags.get(Tags.HTTP_URL));
  }

  @TableTest({
    "scenario | query                                                          | expectedQuery                   ",
    "token    | key1=val1&token=a0b21ce2-006f-4cc6-95d5-d7b550698482&key2=val2 | 'key1=val1&<redacted>&key2=val2'",
    "app keys | app_key=1111&application_key=2222                              | '<redacted>&<redacted>'         ",
    "email    | email=foo@bar.com                                              | email=foo@bar.com               "
  })
  void tagsProcessing(String query, String expectedQuery) {
    QueryObfuscator obfuscator = new QueryObfuscator(null);

    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put(Tags.HTTP_URL, "http://site.com/index");
    tags.put(DDTags.HTTP_QUERY, query);

    TagMap unsafeTags = TagMap.fromMap(tags);
    obfuscator.processTags(unsafeTags, null, link -> {});

    assertEquals(expectedQuery, unsafeTags.get(DDTags.HTTP_QUERY));
    assertEquals("http://site.com/index?" + expectedQuery, unsafeTags.get(Tags.HTTP_URL));
  }

  @TableTest({
    "scenario | query                                                          | expectedQuery                   ",
    "token    | key1=val1&token=a0b21ce2-006f-4cc6-95d5-d7b550698482&key2=val2 | 'key1=val1&<redacted>&key2=val2'",
    "app keys | app_key=1111&application_key=2222                              | '<redacted>&<redacted>'         ",
    "email    | email=foo@bar.com                                              | '<redacted>'                    "
  })
  void tagsProcessingWithCustomRegexpForEmail(String query, String expectedQuery) {
    QueryObfuscator obfuscator = new QueryObfuscator(CUSTOM_OBFUSCATION_PATTERN);

    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put(Tags.HTTP_URL, "http://site.com/index");
    tags.put(DDTags.HTTP_QUERY, query);

    TagMap unsafeTags = TagMap.fromMap(tags);
    obfuscator.processTags(unsafeTags, null, link -> {});

    assertEquals(expectedQuery, unsafeTags.get(DDTags.HTTP_QUERY));
    assertEquals("http://site.com/index?" + expectedQuery, unsafeTags.get(Tags.HTTP_URL));
  }

  private static TagMap process(QueryObfuscator obfuscator, Object query) {
    return process(obfuscator, query, DDTags.HTTP_QUERY);
  }

  private static TagMap process(QueryObfuscator obfuscator, Object query, String queryTag) {
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put(Tags.HTTP_URL, "http://site.com/index");
    tags.put(queryTag, query);

    TagMap unsafeTags = TagMap.fromMap(tags);
    obfuscator.processTags(unsafeTags, null, link -> {});
    return unsafeTags;
  }

  @Test
  void defaultPatternHasExactlyOneCapturingGroup() {
    assertEquals(1, Pattern.compile(QueryObfuscator.DEFAULT_OBFUSCATION_PATTERN).groupCount());
  }

  @TableTest({
    "scenario            | query                         | expectedQuery                ",
    "jwt param           | 'jwt=<JWT>'                   | 'jwt=<redacted>'             ",
    "jwt as own param    | 'a=1&<JWT>&b=2'               | 'a=1&<redacted>&b=2'         ",
    "jwt quoted          | 'x=%22<JWT>%22'               | 'x=%22<redacted>%22'         ",
    "jwt after encoded = | 'x=%3D<JWT>'                  | 'x=%3D<redacted>'            ",
    "jwt alone           | '<JWT>'                       | '<redacted>'                 ",
    "jwt glued to word   | 'x=abc<JWT>'                  | 'x=abc<JWT>'                 ",
    "key lookalikes      | 'keyLength=10&monkeyIsland=1' | 'keyLength=10&monkeyIsland=1'",
    "pwd and jwt         | 'pwd=secret&t=<JWT>'          | '<redacted>&t=<redacted>'    ",
    "jwt after lowercase | 'x=%2c<JWT>'                  | 'x=%2c<redacted>'            ",
    "padding at the end  | 'x=eyJa==.eyJb='              | 'x=<redacted>'               ",
    "padding in segment  | 'x=eyJa=b.eyJc'               | 'x=eyJa=b.eyJc'              ",
    "word ending in ey   | 'heyJude.eyJoe'               | 'heyJude.eyJoe'              "
  })
  void jwtProcessingWithDefaultPattern(String query, String expectedQuery) {
    String input = query.replace("<JWT>", JWT);
    String expected = expectedQuery.replace("<JWT>", JWT);

    TagMap unsafeTags = process(new QueryObfuscator(null), input);

    assertEquals(expected, unsafeTags.getObject(DDTags.HTTP_QUERY));
    assertEquals("http://site.com/index?" + expected, unsafeTags.getObject(Tags.HTTP_URL));
  }

  @Test
  void jwtGluedToPreviousSecretIsNotRedacted() {
    // accepted limitation of the default pattern
    TagMap unsafeTags = process(new QueryObfuscator(null), "{\"password\":\"x\"" + JWT + "}");

    assertEquals("{<redacted>" + JWT + "}", unsafeTags.getObject(DDTags.HTTP_QUERY));
  }

  @Test
  void userRegexWithCapturingGroupDoesNotCopyGroupBack() {
    TagMap unsafeTags = process(new QueryObfuscator("(password=[^&]+)"), "password=hunter2");

    assertEquals("<redacted>", unsafeTags.getObject(DDTags.HTTP_QUERY));
    assertEquals("http://site.com/index?<redacted>", unsafeTags.getObject(Tags.HTTP_URL));
  }

  @Test
  void userCopyOfDefaultPatternAlsoRedactsDelimiter() {
    TagMap unsafeTags =
        process(
            new QueryObfuscator(QueryObfuscator.DEFAULT_OBFUSCATION_PATTERN),
            "a=1&" + JWT + "&b=2");

    assertEquals("a=1<redacted>&b=2", unsafeTags.getObject(DDTags.HTTP_QUERY));
  }

  @TableTest({
    "Scenario      | OTel semantics",
    "Datadog       | false         ",
    "OpenTelemetry | true          "
  })
  void invalidRegexDropsQueryString(boolean otelSemantics) {
    String queryTag = otelSemantics ? "url.query" : DDTags.HTTP_QUERY;
    TagMap unsafeTags =
        process(new QueryObfuscator("(?<=a)b", otelSemantics), "pwd=secret", queryTag);

    assertFalse(unsafeTags.containsKey(queryTag));
    assertEquals("http://site.com/index", unsafeTags.getObject(Tags.HTTP_URL));
  }

  @TableTest({
    "Scenario       | OTel semantics | Query tag        ",
    "Datadog        | false          | http.query.string",
    "OpenTelemetry  | true           | url.query        ",
    "Canonical OTel | true           | http.query.string"
  })
  void obfuscationFailureDropsQueryString(boolean otelSemantics, String queryTag) {
    CharSequence failing =
        new CharSequence() {
          @Override
          public int length() {
            return 0;
          }

          @Override
          public char charAt(int index) {
            throw new IndexOutOfBoundsException();
          }

          @Override
          public CharSequence subSequence(int start, int end) {
            throw new IndexOutOfBoundsException();
          }

          @Override
          public String toString() {
            throw new IllegalStateException("boom");
          }
        };

    TagMap unsafeTags =
        assertDoesNotThrow(
            () -> process(new QueryObfuscator(null, otelSemantics), failing, queryTag));

    assertFalse(unsafeTags.containsKey(queryTag));
    assertEquals("http://site.com/index", unsafeTags.getObject(Tags.HTTP_URL));
  }

  @Test
  void emptyRegexKeepsObfuscationDisabled() {
    String query = "pwd=secret&t=" + JWT;

    TagMap unsafeTags = process(new QueryObfuscator(""), query);

    assertEquals(query, unsafeTags.getObject(DDTags.HTTP_QUERY));
    assertEquals("http://site.com/index?" + query, unsafeTags.getObject(Tags.HTTP_URL));
  }

  @TableTest({
    "Scenario       | OTel semantics | Span type | Retain query | Expected URL                                       ",
    "Datadog server | false          | web       | true         | http://site.com/index#fragment?<redacted>&view=full",
    "Datadog client | false          | http      | true         | http://site.com/index#fragment?<redacted>&view=full",
    "OTel server    | true           | web       | true         | http://site.com/index?<redacted>&view=full#fragment",
    "OTel client    | true           | http      | false        | http://site.com/index?<redacted>&view=full#fragment",
    "No span type   | true           |           | true         | http://site.com/index?<redacted>&view=full#fragment"
  })
  void queryIsRetainedUnlessItOnlyBuildsOtelClientUrlFull(
      boolean otelSemantics, String spanType, boolean retainQuery, String expectedUrl) {
    QueryObfuscator obfuscator = new QueryObfuscator(null, otelSemantics);
    DDSpanContext spanContext = mock(DDSpanContext.class);
    when(spanContext.getSpanType()).thenReturn(spanType);

    TagMap tags = TagMap.create();
    tags.put(Tags.HTTP_URL, "http://site.com/index#fragment");
    String queryTag = otelSemantics ? "url.query" : DDTags.HTTP_QUERY;
    tags.put(queryTag, "token=secret&view=full");

    obfuscator.processTags(tags, spanContext, link -> {});

    assertEquals(expectedUrl, tags.get(Tags.HTTP_URL));
    assertEquals(retainQuery ? "<redacted>&view=full" : null, tags.get(queryTag));
  }
}
