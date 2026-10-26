package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class OtelHttpSemanticsTest {

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "DELETE", "PATCH", "QUERY", "CONNECT"})
  void setRequestMethodKeepsKnownMethods(String method) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setRequestMethod(span, method);

    verify(span).setTag(KnownTags.HTTP_METHOD_ID, method);
    verify(span, never()).setTag(eq(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_ID), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROPFIND", "get", "Get", "BOGUS"})
  void setRequestMethodNormalizesUnknownMethodsToOther(String method) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setRequestMethod(span, method);

    verify(span).setTag(KnownTags.HTTP_METHOD_ID, OtelHttpSemantics.OTHER_METHOD);
    verify(span).setTag(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_ID, method);
  }

  @TableTest({
    "Scenario         | Method   | Expected",
    "GET method       | GET      | GET     ",
    "POST method      | POST     | POST    ",
    "QUERY method     | QUERY    | QUERY   ",
    "Extension method | PROPFIND | HTTP    ",
    "Lowercase method | get      | HTTP    "
  })
  void spanNameMethodUsesNormalizedMethod(String method, String expected) {
    assertEquals(expected, OtelHttpSemantics.spanNameMethod(method));
  }

  @Test
  void spanNameMethodUsesHttpForNullMethod() {
    assertEquals("HTTP", OtelHttpSemantics.spanNameMethod(null));
  }

  @TableTest({
    "Scenario       | Http URL                 | URL path   | Expected                ",
    "No URL or path |                          |            |                         ",
    "URL only       | https://example.com/full |            | https://example.com/full",
    "Path only      |                          | /orders    | /orders                 ",
    "Prefer URL     | https://example.com/full | /different | https://example.com/full",
    "Empty URL      | ''                       | /orders    | ''                      ",
    "Empty path     |                          | ''         | ''                      "
  })
  void urlOrPathFallsBackOnlyWhenUrlIsAbsent(String httpUrl, String path, String expected) {
    Map<String, Object> tags = new HashMap<>();
    tags.put(Tags.HTTP_URL, httpUrl);
    tags.put(KnownTags.URL_PATH_NAME, path);

    assertEquals(expected, OtelHttpSemantics.urlOrPath(tags));
  }

  @Test
  void urlOrPathDoesNotRenderLazyUrl() {
    URIUtils.LazyUrl url = URIUtils.lazyValidURL("https", "example.com", 443, "/orders");
    Map<String, Object> tags = new HashMap<>();
    tags.put(Tags.HTTP_URL, url);
    tags.put(KnownTags.URL_PATH_NAME, "/different");

    assertSame(url, OtelHttpSemantics.urlOrPath(tags));
    assertNull(url.lazy);
  }

  @TableTest({
    "Scenario         | Method   | Expected",
    "Known method     | GET      | GET     ",
    "Extension method | PROPFIND | HTTP    "
  })
  void setMethodResourceNameUsesNormalizedMethod(String method, String expected) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setMethodResourceName(span, method);

    verify(span).setResourceName(expected, ResourceNamePriorities.HTTP_PATH_NORMALIZER);
  }

  @Test
  void setMethodResourceNameUsesHttpForNullMethod() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setMethodResourceName(span, null);

    verify(span).setResourceName("HTTP", ResourceNamePriorities.HTTP_PATH_NORMALIZER);
  }

  @TableTest({
    "Scenario              | Raw URL                             | Expected URL                                 ",
    "No credentials        | http://host:8080/p                  | http://host:8080/p                           ",
    "Username and password | http://user:pass@host/p             | http://REDACTED:REDACTED@host/p              ",
    "Username only         | http://user@host/p                  | http://REDACTED@host/p                       ",
    "Query and fragment    | https://u:p@host:443/a?b=c#frag     | https://REDACTED:REDACTED@host:443/a?b=c#frag",
    "Credentials in path   | https://u:p@host/u:p@path           | https://REDACTED:REDACTED@host/u:p@path      ",
    "Underscore host       | http://user:pass@foo_bar/p          | http://REDACTED:REDACTED@foo_bar/p           ",
    "Underscore username   | http://user_@host/p                 | http://REDACTED@host/p                       ",
    "Unicode host          | http://user:pass@例え.テスト/p      | http://REDACTED:REDACTED@例え.テスト/p       ",
    "Encoded credentials   | http://u%40ser:p%40ss@foo_bar/p     | http://REDACTED:REDACTED@foo_bar/p           ",
    "At sign in password   | http://user:p@ss@foo_bar/p          | http://REDACTED:REDACTED@foo_bar/p           ",
    "Empty username        | http://:pass@foo_bar/p              | http://REDACTED:REDACTED@foo_bar/p           ",
    "IPv6 host             | http://user:pass@[::1]:8080/p       | http://REDACTED:REDACTED@[::1]:8080/p        ",
    "IPv6 username only    | http://user@[::1]:8080/p            | http://REDACTED@[::1]:8080/p                 ",
    "Scheme relative URL   | //user:pass@foo_bar/p               | //REDACTED:REDACTED@foo_bar/p                ",
    "At signs outside host | http://foo_bar/u:p@path?q=u:p@query | http://foo_bar/u:p@path?q=u:p@query          ",
    "Relative path         | /u:p@path                           | /u:p@path                                    ",
    "Opaque URI            | mailto:user:pass@host               | mailto:user:pass@host                        "
  })
  void redactedUrlRedactsCredentials(String raw, String expected) {
    assertEquals(expected, OtelHttpSemantics.redactedUrl(URI.create(raw)));
  }

  @TableTest({
    "Scenario                  | URL                        | Expected              ",
    "Query removed             | http://host/p?token=secret | http://host/p         ",
    "Fragment retained         | http://host/p#frag         | http://host/p#frag    ",
    "Query before fragment     | http://host/p?q=1#frag     | http://host/p#frag    ",
    "Question mark in fragment | http://host/p#frag?q=1     | http://host/p#frag?q=1",
    "No query or fragment      | http://host/p              | http://host/p         "
  })
  void withoutQueryPreservesFragment(String url, String expected) {
    assertEquals(expected, OtelHttpSemantics.withoutQuery(url));
  }

  @TableTest({
    "Scenario       | URL               | Expected Port",
    "Explicit port  | http://host:8080/ | 8080         ",
    "HTTP default   | http://host/      | 80           ",
    "HTTPS default  | https://host/     | 443          ",
    "Unknown scheme | ftp://host/       | -1           "
  })
  void serverPortFallsBackToSchemeDefault(String url, int expectedPort) {
    assertEquals(expectedPort, OtelHttpSemantics.serverPort(URI.create(url)));
  }

  @Test
  void setErrorTypeSetsStatusWhenAbsent() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(DDTags.ERROR_TYPE)).thenReturn(null);

    OtelHttpSemantics.setErrorType(span, 500);

    verify(span).setTag(KnownTags.ERROR_TYPE_ID, "500");
  }

  @Test
  void setErrorTypeDoesNotOverrideExistingErrorType() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(DDTags.ERROR_TYPE)).thenReturn("java.net.UnknownHostException");

    OtelHttpSemantics.setErrorType(span, 500);

    verify(span, never()).setTag(eq(KnownTags.ERROR_TYPE_ID), any(String.class));
  }

  @TableTest({
    "Scenario             | Header                             | Expected",
    "Single host          | {host, '  host  '}                 | host    ",
    "First forwarded host | {'host, proxy', '  host  , proxy'} | host    "
  })
  void forwardedValueUsesFirstTrimmedEntry(String header, String expected) {
    assertEquals(expected, OtelHttpSemantics.forwardedValue(header));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", ", proxy"})
  void forwardedValueReturnsNullForBlankFirstEntry(String header) {
    assertNull(OtelHttpSemantics.forwardedValue(header));
  }

  @Test
  void forwardedValueReturnsNullForNullHeader() {
    assertNull(OtelHttpSemantics.forwardedValue(null));
  }

  @TableTest({
    "Scenario         | Value                                            | Expected",
    "Valid port       | 8080                                             | 8080    ",
    "Minimum port     | 1                                                | 1       ",
    "Maximum port     | 65535                                            | 65535   ",
    "Leading zeros    | 00080                                            | 80      ",
    "Out of range     | {0, 65536, 99999}                                | -1      ",
    "Malformed port   | {abc, 80x, 8.0, ' 80', '80 '}                    | -1      ",
    "Signed port      | {+80, -1}                                        | -1      ",
    "Non-ASCII digits | {８０, ٨٠}                                       | -1      ",
    "Excess digits    | {100000, 000080, 008080, 2147483647, 2147483648} | -1      "
  })
  void parsePortRejectsOutOfRangeAndMalformedValues(String value, int expected) {
    assertEquals(expected, OtelHttpSemantics.parsePort(value));
  }

  @Test
  void parsePortReturnsUnsetForEmptyValue() {
    assertEquals(-1, OtelHttpSemantics.parsePort(""));
  }

  @Test
  void parsePortReturnsUnsetForNullValue() {
    assertEquals(-1, OtelHttpSemantics.parsePort(null));
  }

  @TableTest({
    "Scenario       | Scheme         | Port | Expected",
    "HTTPS default  | {https, HTTPS} | -1   | 443     ",
    "HTTP default   | {http, HTTP}   | -1   | 80      ",
    "Unknown scheme | ftp            | -1   | -1      "
  })
  void serverPortUsesSchemeDefaultWhenPortUnset(String scheme, int port, int expected) {
    assertEquals(expected, OtelHttpSemantics.serverPort(scheme, port));
  }

  @Test
  void serverPortReturnsUnsetForNullScheme() {
    assertEquals(-1, OtelHttpSemantics.serverPort(null, -1));
  }

  @TableTest({
    "Scenario           | Host                      | Expected port",
    "No host            |                           | -1           ",
    "Empty host         | ''                        | -1           ",
    "Host only          | public.example.com        | -1           ",
    "Empty port         | public.example.com:       | -1           ",
    "Explicit port      | public.example.com:9443   | 9443         ",
    "Minimum port       | public.example.com:1      | 1            ",
    "Maximum port       | public.example.com:65535  | 65535        ",
    "Leading zeros      | public.example.com:00080  | 80           ",
    "Excess digits      | public.example.com:008080 | -1           ",
    "Signed port        | {'host:+80', 'host:-1'}   | -1           ",
    "Whitespace         | {'host: 80', 'host:80 '}  | -1           ",
    "Non-ASCII digits   | {'host:８０', 'host:٨٠'}  | -1           ",
    "Invalid port       | public.example.com:bad    | -1           ",
    "Port out of range  | public.example.com:65536  | -1           ",
    "IPv6 host          | '[2001:db8::1]'           | -1           ",
    "IPv6 explicit port | '[2001:db8::1]:8443'      | 8443         ",
    "Unbracketed IPv6   | 2001:db8::1:8443          | -1           ",
    "Malformed IPv6     | '[2001:db8::1:8443'       | -1           "
  })
  void portFromHostRequiresAnExplicitValidPort(String host, int expectedPort) {
    assertEquals(expectedPort, OtelHttpSemantics.portFromHost(host));
  }
}
