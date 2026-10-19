package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.re2j.Pattern;
import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
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
    String queryTag = otelSemantics ? DDTags.HTTP_QUERY_OTEL_NAME : DDTags.HTTP_QUERY;
    TagMap unsafeTags =
        process(new QueryObfuscator("(?<=a)b", otelSemantics), "pwd=secret", queryTag);

    assertFalse(unsafeTags.containsKey(queryTag));
    assertEquals("http://site.com/index", unsafeTags.getObject(Tags.HTTP_URL));
  }

  @TableTest({
    "Scenario       | OTel semantics | Query tag        ",
    "Datadog        | false          | http.query.string",
    "OpenTelemetry  | true           | url.query        ",
    "Legacy in OTel | true           | http.query.string"
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
    "Scenario    | Legacy query     | OTel query        | Expected legacy | Expected OTel       | Expected URL query ",
    "Legacy only | token=legacy     |                   | <redacted>      |                     | <redacted>         ",
    "OTel only   |                  | pwd=otel&view=all |                 | <redacted>&view=all | <redacted>&view=all",
    "Both keys   | token=legacy&a=1 | pwd=otel&b=2      | <redacted>&a=1  | <redacted>&b=2      | <redacted>&b=2     "
  })
  void otelSemanticsObfuscatesBothQueryTags(
      String legacyQuery,
      String otelQuery,
      String expectedLegacy,
      String expectedOtel,
      String expectedUrlQuery) {
    TagMap tags = TagMap.create();
    tags.put(Tags.HTTP_URL, "http://site.com/index#fragment");
    if (legacyQuery != null) {
      tags.put(DDTags.HTTP_QUERY, legacyQuery);
    }
    if (otelQuery != null) {
      tags.put(DDTags.HTTP_QUERY_OTEL_NAME, otelQuery);
    }

    new QueryObfuscator(null, true).processTags(tags, null, link -> {});

    assertEquals(expectedLegacy, tags.get(DDTags.HTTP_QUERY));
    assertEquals(expectedOtel, tags.get(DDTags.HTTP_QUERY_OTEL_NAME));
    assertEquals(
        "http://site.com/index?" + expectedUrlQuery + "#fragment", tags.get(Tags.HTTP_URL));
  }

  @TableTest({
    "Scenario     | Failing tag       | Other tag        ",
    "Legacy fails | http.query.string | url.query        ",
    "OTel fails   | url.query         | http.query.string"
  })
  void failureInOneQueryTagDoesNotPreventObfuscatingTheOther(String failingTag, String otherTag) {
    CharSequence query = mock(CharSequence.class);
    when(query.toString()).thenThrow(new IllegalStateException("boom"));
    TagMap tags = TagMap.create();
    tags.put(Tags.HTTP_URL, "http://site.com/index#fragment");
    tags.put(failingTag, query);
    tags.put(otherTag, "token=secret");

    assertDoesNotThrow(() -> new QueryObfuscator(null, true).processTags(tags, null, link -> {}));

    assertFalse(tags.containsKey(failingTag));
    assertEquals("<redacted>", tags.get(otherTag));
    assertEquals("http://site.com/index?<redacted>#fragment", tags.get(Tags.HTTP_URL));
  }

  @Test
  void invalidRegexDropsBothQueryTagsInOtelMode() {
    TagMap tags = TagMap.create();
    tags.put(Tags.HTTP_URL, "http://site.com/index#fragment");
    tags.put(DDTags.HTTP_QUERY, "token=legacy");
    tags.put(DDTags.HTTP_QUERY_OTEL_NAME, "pwd=otel");

    new QueryObfuscator("(?<=a)b", true).processTags(tags, null, link -> {});

    assertFalse(tags.containsKey(DDTags.HTTP_QUERY));
    assertFalse(tags.containsKey(DDTags.HTTP_QUERY_OTEL_NAME));
    assertEquals("http://site.com/index#fragment", tags.get(Tags.HTTP_URL));
  }

  @TableTest({
    "spanType | retainQuery",
    "http     | false      ",
    "web      | true       "
  })
  void otelClientQueryIsOnlyUsedToBuildUrlFull(String spanType, boolean retainQuery) {
    QueryObfuscator obfuscator = new QueryObfuscator(null, true);
    DDSpanContext spanContext = mock(DDSpanContext.class);
    when(spanContext.getSpanType()).thenReturn(spanType);

    TagMap tags = TagMap.create();
    tags.put(Tags.HTTP_URL, "http://site.com/index#fragment");
    tags.put(KnownTags.URL_QUERY_NAME, "token=secret&otel_value=retained");
    tags.put(DDTags.HTTP_QUERY, "pwd=legacy&dd_value=not_kept");

    obfuscator.processTags(tags, spanContext, link -> {});

    assertEquals(
        "http://site.com/index?<redacted>&otel_value=retained#fragment", tags.get(Tags.HTTP_URL));
    assertEquals("<redacted>&otel_value=retained", tags.get(DDTags.HTTP_QUERY));
    if (retainQuery) {
      assertEquals("<redacted>", tags.get(KnownTags.URL_QUERY_NAME));
      return;
    }
    assertNull(tags.get(KnownTags.URL_QUERY_NAME));
  }
}
