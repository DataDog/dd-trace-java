package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import org.junit.jupiter.api.Test;

class HttpOtelSemanticsTagsPostProcessorTest {

  private final HttpOtelSemanticsTagsPostProcessor processor =
      new HttpOtelSemanticsTagsPostProcessor();

  private static DDSpanContext ctx(final String spanType, final int status) {
    final DDSpanContext ctx = mock(DDSpanContext.class);
    when(ctx.getSpanType()).thenReturn(spanType);
    when(ctx.getHttpStatusCode()).thenReturn((short) status);
    return ctx;
  }

  private void process(final TagMap tags, final DDSpanContext ctx) {
    processor.processTags(tags, ctx, link -> {});
  }

  private static int intTag(final TagMap tags, final String key) {
    return ((Number) tags.getObject(key)).intValue();
  }

  @Test
  void clientRenamesHttpAttributesToOtelNames() {
    final TagMap tags = TagMap.create();
    tags.set(Tags.HTTP_METHOD, "GET");
    // QueryObfuscator already folded the (obfuscated) query into http.url
    tags.set(Tags.HTTP_URL, "http://user:pass@host:8080/p?token=<redacted>");
    tags.set(Tags.PEER_HOSTNAME, "host");
    tags.set(Tags.PEER_PORT, 8080);

    process(tags, ctx(DDSpanTypes.HTTP_CLIENT, 200));

    assertEquals("GET", tags.getString(Tags.HTTP_REQUEST_METHOD));
    assertEquals("200", tags.getString(Tags.HTTP_RESPONSE_STATUS_CODE));
    assertEquals(
        "http://REDACTED:REDACTED@host:8080/p?token=<redacted>", tags.getString(Tags.URL_FULL));
    assertEquals("host", tags.getString(Tags.SERVER_ADDRESS));
    assertEquals(8080, intTag(tags, Tags.SERVER_PORT));
    // Datadog names are gone
    assertNull(tags.getObject(Tags.HTTP_METHOD));
    assertNull(tags.getObject(Tags.HTTP_URL));
    assertNull(tags.getObject(Tags.PEER_HOSTNAME));
    assertNull(tags.getObject(Tags.PEER_PORT));
  }

  @Test
  void clientServerPortFallsBackToSchemeDefault() {
    final TagMap tags = TagMap.create();
    tags.set(Tags.HTTP_METHOD, "GET");
    tags.set(Tags.HTTP_URL, "https://host/p"); // no port, no peer.port
    process(tags, ctx(DDSpanTypes.HTTP_CLIENT, 200));
    assertEquals(443, intTag(tags, Tags.SERVER_PORT));
  }

  @Test
  void serverRenamesHttpAttributesToOtelNames() {
    final TagMap tags = TagMap.create();
    tags.set(Tags.HTTP_METHOD, "POST");
    // obfuscated query appended to http.url, plus the separate (obfuscated) http.query
    tags.set(Tags.HTTP_URL, "http://example.com:8080/users?q=<redacted>");
    tags.set(Tags.HTTP_HOSTNAME, "example.com");
    tags.set(DDTags.HTTP_QUERY, "q=<redacted>");
    tags.set(Tags.HTTP_USER_AGENT, "curl/8");
    tags.set(Tags.HTTP_CLIENT_IP, "1.2.3.4");
    tags.set(Tags.NETWORK_CLIENT_IP, "5.6.7.8");

    process(tags, ctx(DDSpanTypes.HTTP_SERVER, 200));

    assertEquals("POST", tags.getString(Tags.HTTP_REQUEST_METHOD));
    assertEquals("example.com", tags.getString(Tags.SERVER_ADDRESS));
    assertEquals("http", tags.getString(Tags.URL_SCHEME));
    assertEquals("/users", tags.getString(Tags.URL_PATH));
    assertEquals(8080, intTag(tags, Tags.SERVER_PORT));
    assertEquals("q=<redacted>", tags.getString(Tags.URL_QUERY));
    assertEquals("curl/8", tags.getString(Tags.USER_AGENT_ORIGINAL));
    assertEquals("1.2.3.4", tags.getString(Tags.CLIENT_ADDRESS));
    assertEquals("5.6.7.8", tags.getString(Tags.NETWORK_PEER_ADDRESS));
    // Datadog names are gone
    assertNull(tags.getObject(Tags.HTTP_HOSTNAME));
    assertNull(tags.getObject(Tags.HTTP_URL));
    assertNull(tags.getObject(Tags.HTTP_USER_AGENT));
    assertNull(tags.getObject(Tags.HTTP_CLIENT_IP));
    assertNull(tags.getObject(Tags.NETWORK_CLIENT_IP));
    assertNull(tags.getObject(DDTags.HTTP_QUERY));
  }

  @Test
  void unknownMethodNormalizesToOther() {
    final TagMap tags = TagMap.create();
    tags.set(Tags.HTTP_METHOD, "PROPFIND");
    process(tags, ctx(DDSpanTypes.HTTP_SERVER, 200));
    assertEquals("_OTHER", tags.getString(Tags.HTTP_REQUEST_METHOD));
    assertEquals("PROPFIND", tags.getString(Tags.HTTP_REQUEST_METHOD_ORIGINAL));
    assertNull(tags.getObject(Tags.HTTP_METHOD));
  }

  @Test
  void serverErrorTypeOnlyOn5xx() {
    final TagMap server4xx = TagMap.create();
    server4xx.set(Tags.HTTP_METHOD, "GET");
    process(server4xx, ctx(DDSpanTypes.HTTP_SERVER, 404));
    assertEquals("404", server4xx.getString(Tags.HTTP_RESPONSE_STATUS_CODE));
    assertNull(server4xx.getObject(DDTags.ERROR_TYPE));

    final TagMap server5xx = TagMap.create();
    server5xx.set(Tags.HTTP_METHOD, "GET");
    process(server5xx, ctx(DDSpanTypes.HTTP_SERVER, 500));
    assertEquals("500", server5xx.getString(DDTags.ERROR_TYPE));
  }

  @Test
  void clientErrorTypeOn4xxAnd5xx() {
    final TagMap client4xx = TagMap.create();
    client4xx.set(Tags.HTTP_METHOD, "GET");
    process(client4xx, ctx(DDSpanTypes.HTTP_CLIENT, 404));
    assertEquals("404", client4xx.getString(DDTags.ERROR_TYPE));
  }

  @Test
  void errorTypeDoesNotClobberExistingValue() {
    final TagMap tags = TagMap.create();
    tags.set(Tags.HTTP_METHOD, "GET");
    tags.set(DDTags.ERROR_TYPE, "java.net.SocketTimeoutException");
    process(tags, ctx(DDSpanTypes.HTTP_SERVER, 500));
    assertEquals("java.net.SocketTimeoutException", tags.getString(DDTags.ERROR_TYPE));
  }

  @Test
  void nonHttpSpanIsLeftUntouched() {
    final TagMap tags = TagMap.create();
    tags.set(Tags.HTTP_METHOD, "GET");
    process(tags, ctx("sql", 0));
    // no rename happened
    assertEquals("GET", tags.getString(Tags.HTTP_METHOD));
    assertNull(tags.getObject(Tags.HTTP_REQUEST_METHOD));
  }

  @Test
  void isIdempotent() {
    final TagMap tags = TagMap.create();
    tags.set(Tags.HTTP_METHOD, "GET");
    tags.set(Tags.HTTP_URL, "http://host:80/p");
    tags.set(Tags.PEER_HOSTNAME, "host");
    final DDSpanContext ctx = ctx(DDSpanTypes.HTTP_CLIENT, 200);
    process(tags, ctx);
    process(tags, ctx); // second pass must be a no-op
    assertEquals("GET", tags.getString(Tags.HTTP_REQUEST_METHOD));
    assertEquals("host", tags.getString(Tags.SERVER_ADDRESS));
  }
}
