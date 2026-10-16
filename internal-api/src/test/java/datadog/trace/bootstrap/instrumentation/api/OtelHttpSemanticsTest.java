package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils.LazyUrl;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;

class OtelHttpSemanticsTest {

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "DELETE", "PATCH", "QUERY", "CONNECT"})
  void setRequestMethodKeepsKnownMethods(String method) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setRequestMethod(span, method);

    verify(span).setTag(KnownTags.HTTP_METHOD_OTEL_NAME, method);
    verify(span, never()).setTag(eq(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROPFIND", "get", "Get", "BOGUS"})
  void setRequestMethodNormalizesUnknownMethodsToOther(String method) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setRequestMethod(span, method);

    verify(span).setTag(KnownTags.HTTP_METHOD_OTEL_NAME, OtelHttpSemantics.OTHER_METHOD);
    verify(span).setTag(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME, method);
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

  @Test
  void setClientUrlLazilyStripsQueryAndRedactsCredentials() {
    AgentSpan span = mock(AgentSpan.class);
    ArgumentCaptor<LazyUrl> urlCaptor = ArgumentCaptor.forClass(LazyUrl.class);

    OtelHttpSemantics.setClientUrl(span, URI.create("https://u:p@host/a?token=secret#fragment"));

    verify(span).setTag(eq(KnownTags.HTTP_URL_OTEL_NAME), (Object) urlCaptor.capture());
    LazyUrl url = urlCaptor.getValue();

    // Reading the path must leave the full URL unrendered.
    assertEquals("/a", url.path());
    assertNull(url.lazy);
    assertEquals("https://REDACTED:REDACTED@host/a#fragment", url.toString());
    verify(span).setTag(KnownTags.URL_QUERY_NAME, "token=secret");
  }

  @Test
  void setClientUrlOmitsQueryTagWhenAbsent() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setClientUrl(span, URI.create("http://host/a"));

    verify(span, never()).setTag(eq(KnownTags.URL_QUERY_NAME), anyString());
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

    verify(span).setTag(DDTags.ERROR_TYPE, "500");
  }

  @Test
  void setErrorTypeDoesNotOverrideExistingErrorType() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(DDTags.ERROR_TYPE)).thenReturn("java.net.UnknownHostException");

    OtelHttpSemantics.setErrorType(span, 500);

    verify(span, never()).setTag(eq(DDTags.ERROR_TYPE), any(String.class));
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
    "Scenario         | Value                                    | Expected",
    "Valid port       | 8080                                     | 8080    ",
    "Minimum port     | 1                                        | 1       ",
    "Maximum port     | 65535                                    | 65535   ",
    "Leading zeros    | 00080                                    | 80      ",
    "Out of range     | {0, 65536, 99999}                        | -1      ",
    "Malformed port   | {abc, 80x, 8.0, ' 80', '80 '}            | -1      ",
    "Signed port      | {+80, -1}                                | -1      ",
    "Non-ASCII digits | {８０, ٨٠}                               | -1      ",
    "Excess digits    | {100000, 000080, 2147483647, 2147483648} | -1      "
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

  @Test
  void setServerAddressAndPortUsesRequestHostAndPort() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setServerAddressAndPort(span, null, null, "http", "host", 8080);

    verify(span).setTag(KnownTags.HTTP_HOSTNAME_NAME, "host");
    verify(span).setTag(KnownTags.SERVER_PORT_NAME, 8080);
  }

  @Test
  void setServerAddressAndPortFallsBackToSchemeDefaultPort() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setServerAddressAndPort(span, null, null, "https", "host", -1);

    verify(span).setTag(KnownTags.HTTP_HOSTNAME_NAME, "host");
    verify(span).setTag(KnownTags.SERVER_PORT_NAME, 443);
  }

  @Test
  void setServerAddressAndPortOmitsTagsWhenNothingResolvable() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setServerAddressAndPort(span, null, null, "ftp", null, -1);

    verify(span, never()).setTag(eq(KnownTags.HTTP_HOSTNAME_NAME), anyString());
    verify(span, never()).setTag(eq(KnownTags.SERVER_PORT_NAME), anyInt());
  }

  @Test
  void setServerAddressAndPortOmitsAddressWhenRequestHostIsEmpty() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setServerAddressAndPort(span, null, null, "http", "", -1);

    verify(span, never()).setTag(eq(KnownTags.HTTP_HOSTNAME_NAME), anyString());
    verify(span).setTag(KnownTags.SERVER_PORT_NAME, 80);
  }

  @Test
  void setServerAddressAndPortPrefersForwardedPortHeader() {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setServerAddressAndPort(span, "proxy:8443", "9443", "http", "host", 8080);

    verify(span).setTag(KnownTags.HTTP_HOSTNAME_NAME, "proxy");
    verify(span).setTag(KnownTags.SERVER_PORT_NAME, 9443);
  }

  @TableTest({
    "Scenario             | Forwarded Host | Forwarded Port | Expected Port",
    "Host port            | proxy:8443     | bogus          | 8443         ",
    "Excess header digits | proxy:8443     | 000080         | 8443         ",
    "Scheme default       | proxy          | bogus          | 80           ",
    "Request port         |                | bogus          | 8080         "
  })
  void setServerAddressAndPortFallsBackFromInvalidForwardedPort(
      String forwardedHost, String forwardedPort, int expectedPort) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setServerAddressAndPort(
        span, forwardedHost, forwardedPort, "http", "host", 8080);

    verify(span).setTag(KnownTags.SERVER_PORT_NAME, expectedPort);
  }

  @TableTest({
    "Scenario                 | Forwarded Host | Expected Address | Expected Port",
    "Host only                | proxy          | proxy            | 80           ",
    "Host with port           | proxy:8443     | proxy            | 8443         ",
    "Host with invalid port   | proxy:bogus    | proxy            | 80           ",
    "IPv6 host                | '[::1]'        | ::1              | 80           ",
    "IPv6 with port           | '[::1]:8443'   | ::1              | 8443         ",
    "IPv6 with invalid suffix | '[::1]bogus'   | ::1              | 80           ",
    "Unbracketed IPv6         | ::1            | ::1              | 80           ",
    "Unclosed IPv6 bracket    | '[::1'         | '[::1'           | 80           "
  })
  void setServerAddressAndPortParsesForwardedHost(
      String forwardedHost, String expectedAddress, int expectedPort) {
    AgentSpan span = mock(AgentSpan.class);

    OtelHttpSemantics.setServerAddressAndPort(span, forwardedHost, null, "http", "host", 8080);

    verify(span).setTag(KnownTags.HTTP_HOSTNAME_NAME, expectedAddress);
    // A forwarded host suppresses the request port, so the scheme default applies instead.
    verify(span).setTag(KnownTags.SERVER_PORT_NAME, expectedPort);
  }
}
