package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import datadog.trace.bootstrap.instrumentation.api.http.DatadogClientUrl;
import datadog.trace.bootstrap.instrumentation.api.http.DatadogServerUrl;
import datadog.trace.bootstrap.instrumentation.api.http.HttpServerUrl;
import datadog.trace.bootstrap.instrumentation.api.http.OtelClientUrl;
import datadog.trace.bootstrap.instrumentation.api.http.OtelServerUrl;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;

@WithConfig(key = "http.client.tag.query-string", value = "true")
@WithConfig(key = "http.server.tag.query-string", value = "true")
class HttpUrlExtractorsTest {
  private final AgentSpan span = mock(AgentSpan.class);

  @Test
  void otelClientLazilyStripsQueryAndRedactsCredentials() {
    ArgumentCaptor<LazyUrl> urlCaptor = ArgumentCaptor.forClass(LazyUrl.class);

    OtelClientUrl.INSTANCE.extract(URI.create("https://u:p@host/a?token=secret#fragment"), span);

    verify(span).setTag(eq(KnownTags.HTTP_URL_ID), (Object) urlCaptor.capture());
    LazyUrl url = urlCaptor.getValue();

    // Reading the path must leave the full URL unrendered.
    assertEquals("/a", url.path());
    assertNull(url.lazy);
    assertEquals("https://REDACTED:REDACTED@host/a#fragment", url.toString());
    assertEquals("https://REDACTED:REDACTED@host/a#fragment", url.get());
    verify(span).setTag(KnownTags.HTTP_QUERY_STRING_ID, "token=secret");
  }

  @TableTest({
    "Scenario               | Raw URL                                | Expected query        ",
    "Plain name             | http://host/a?token=secret             | token=secret          ",
    "Encoded name           | http://host/a?t%6fken=secret           | token=secret          ",
    "Leading encoded name   | http://host/a?%74oken=secret           | token=secret          ",
    "Encoded name and value | http://host/a?t%6fken=s%65cret         | token=secret          ",
    "Encoded space in value | http://host/a?q=a%20b                  | q=a b                 ",
    "Multiple parameters    | http://host/a?view=full&t%6fken=secret | view=full&token=secret"
  })
  void otelClientDecodesQuerySoTheObfuscatorCanMatchEncodedNames(
      String rawUrl, String expectedQuery) {
    OtelClientUrl.INSTANCE.extract(URI.create(rawUrl), span);

    // The obfuscator matches literal parameter names, so the query it receives must be decoded.
    verify(span).setTag(KnownTags.HTTP_QUERY_STRING_ID, expectedQuery);
  }

  @Test
  void otelClientOmitsQueryTagWhenAbsent() {
    OtelClientUrl.INSTANCE.extract(URI.create("http://host/a"), span);

    verify(span, never()).setTag(eq(KnownTags.HTTP_QUERY_STRING_ID), anyString());
  }

  @Test
  void extractServerUrlUsesRequestHostAndPort() {
    extractServerUrl(null, null, "http", "host", 8080);

    verify(span).setTag(KnownTags.HTTP_HOSTNAME_ID, "host");
    verify(span).setTag(KnownTags.Inbound.SERVER_PORT_ID, 8080);
  }

  @Test
  void extractServerUrlFallsBackToSchemeDefaultPort() {
    extractServerUrl(null, null, "https", "host", -1);

    verify(span).setTag(KnownTags.HTTP_HOSTNAME_ID, "host");
    verify(span).setTag(KnownTags.Inbound.SERVER_PORT_ID, 443);
  }

  @Test
  void extractServerUrlOmitsTagsWhenNothingResolvable() {
    extractServerUrl(null, null, "ftp", null, -1);

    verify(span, never()).setTag(eq(KnownTags.HTTP_HOSTNAME_ID), anyString());
    verify(span, never()).setTag(eq(KnownTags.Inbound.SERVER_PORT_ID), anyInt());
  }

  @Test
  void extractServerUrlOmitsAddressWhenRequestHostIsEmpty() {
    extractServerUrl(null, null, "http", "", -1);

    verify(span, never()).setTag(eq(KnownTags.HTTP_HOSTNAME_ID), anyString());
    verify(span).setTag(KnownTags.Inbound.SERVER_PORT_ID, 80);
  }

  @Test
  void extractServerUrlPrefersForwardedPortHeader() {
    extractServerUrl("proxy:8443", "9443", "http", "host", 8080);

    verify(span).setTag(KnownTags.HTTP_HOSTNAME_ID, "proxy");
    verify(span).setTag(KnownTags.Inbound.SERVER_PORT_ID, 9443);
  }

  @TableTest({
    "Scenario             | Forwarded Host | Forwarded Port | Expected Port",
    "Host port            | proxy:8443     | bogus          | 8443         ",
    "Excess header digits | proxy:8443     | 000080         | 8443         ",
    "Scheme default       | proxy          | bogus          | 80           ",
    "Request port         |                | bogus          | 8080         "
  })
  void extractServerUrlFallsBackFromInvalidForwardedPort(
      String forwardedHost, String forwardedPort, int expectedPort) {
    extractServerUrl(forwardedHost, forwardedPort, "http", "host", 8080);

    verify(span).setTag(KnownTags.Inbound.SERVER_PORT_ID, expectedPort);
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
  void extractServerUrlParsesForwardedHost(
      String forwardedHost, String expectedAddress, int expectedPort) {
    extractServerUrl(forwardedHost, null, "http", "host", 8080);

    verify(span).setTag(KnownTags.HTTP_HOSTNAME_ID, expectedAddress);
    // A forwarded host suppresses the request port, so the scheme default applies instead.
    verify(span).setTag(KnownTags.Inbound.SERVER_PORT_ID, expectedPort);
  }

  @Test
  void datadogClientKeepsNormalizedUrlAndSeparateQueryAndFragment() {
    DatadogClientUrl.INSTANCE.extract(URI.create("https://u:p@host:443/a?q=1#fragment"), span);

    ArgumentCaptor<CharSequence> url = ArgumentCaptor.forClass(CharSequence.class);
    verify(span).setTag(eq(KnownTags.HTTP_URL_ID), url.capture());
    assertEquals("https://host/a", url.getValue().toString());
    verify(span).setTag(KnownTags.HTTP_QUERY_STRING_ID, "q=1");
    verify(span).setTag(DDTags.HTTP_FRAGMENT, "fragment");
  }

  @Test
  @WithConfig(key = "http.client.tag.query-string", value = "false")
  void datadogUrlConnectionKeepsOriginalUrl() {
    String url = "http://u:p@host:80/a?q=1#fragment";

    DatadogClientUrl.FULL_URL.extract(URI.create(url), span);

    verify(span).setTag(KnownTags.HTTP_URL_ID, url);
    verify(span, never()).setTag(eq(KnownTags.HTTP_QUERY_STRING_ID), anyString());
  }

  @Test
  @WithConfig(key = "http.client.tag.query-string", value = "false")
  void otelClientRespectsQueryOptOut() {
    OtelClientUrl.INSTANCE.extract(URI.create("http://host/a?token=secret"), span);

    verify(span, never()).setTag(eq(KnownTags.HTTP_QUERY_STRING_ID), anyString());
  }

  @Test
  void datadogServerKeepsBackendUrlAndOriginalForwardedHostname() {
    URI uri = URI.create("http://internal:8080/a?q=1#fragment");
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    when(extracted.getXForwardedHost()).thenReturn("public:8443, proxy");
    when(extracted.getXForwardedProto()).thenReturn("https");

    DatadogServerUrl.INSTANCE.extract(
        HttpServerUrl.fromRequest(new URIDefaultDataAdapter(uri), "/a", extracted, null), span);

    ArgumentCaptor<CharSequence> url = ArgumentCaptor.forClass(CharSequence.class);
    verify(span).setTag(eq(KnownTags.HTTP_URL_ID), url.capture());
    assertEquals("http://internal:8080/a", url.getValue().toString());
    verify(span).setTag(KnownTags.HTTP_HOSTNAME_ID, "public:8443, proxy");
    verify(span).setTag(KnownTags.HTTP_QUERY_STRING_ID, "q=1");
    verify(span).setTag(DDTags.HTTP_FRAGMENT, "fragment");
    verify(extracted, never()).getXForwardedProto();
    verify(extracted, never()).getXForwardedPort();
  }

  @TableTest({
    "Scenario     | Original URL                         | Host       | Port | Expected host | Expected port",
    "State origin | https://url-host:8443/a?token=secret | state-host | 9443 | state-host    | 9443         ",
    "URL origin   | https://url-host:8443/a?token=secret |            | bad  | url-host      | 8443         ",
    "Default port | https://url-host/a?token=secret      |            |      | url-host      | 443          ",
    "Invalid URL  | /bad%path                            | state-host | 9443 | state-host    | 9443         "
  })
  void transactionPreservesOriginalUrlAndResolvesOtelOrigin(
      String originalUrl, String host, String port, String expectedHost, int expectedPort) {
    HttpServerUrl url = HttpServerUrl.fromTransaction(originalUrl, host, port);
    AgentSpan datadog = mock(AgentSpan.class);

    DatadogServerUrl.URL_ONLY.extract(url, datadog);
    OtelServerUrl.INSTANCE.extract(url, span);

    verify(datadog).setTag(KnownTags.HTTP_URL_ID, (CharSequence) originalUrl);
    verify(datadog, never()).setTag(eq(KnownTags.HTTP_HOSTNAME_ID), anyString());
    verify(span).setTag(KnownTags.Inbound.SERVER_ADDRESS_ID, expectedHost);
    verify(span).setTag(KnownTags.Inbound.SERVER_PORT_ID, expectedPort);
  }

  @Test
  void transactionExtractsUrlComponents() {
    OtelServerUrl.INSTANCE.extract(
        HttpServerUrl.fromTransaction("https://host/a?token=secret", null, null), span);

    verify(span).setTag(KnownTags.URL_SCHEME_ID, "https");
    verify(span).setTag(KnownTags.URL_PATH_ID, "/a");
    verify(span).setTag(KnownTags.HTTP_QUERY_STRING_ID, "token=secret");
  }

  @Test
  @WithConfig(key = "http.server.tag.query-string", value = "false")
  void transactionRespectsQueryOptOut() {
    OtelServerUrl.INSTANCE.extract(
        HttpServerUrl.fromTransaction("https://host/a?token=secret", null, null), span);

    verify(span, never()).setTag(eq(KnownTags.HTTP_QUERY_STRING_ID), anyString());
  }

  @Test
  void inferredProxySupportsBothUrlStyles() {
    HttpServerUrl url = HttpServerUrl.fromInferredProxy("api.host", "/a");
    AgentSpan datadog = mock(AgentSpan.class);

    DatadogServerUrl.URL_ONLY.extract(url, datadog);
    OtelServerUrl.INSTANCE.extract(url, span);

    verify(datadog).setTag(KnownTags.HTTP_URL_ID, (CharSequence) "https://api.host/a");
    verify(span).setTag(KnownTags.URL_PATH_ID, "/a");
    verify(span).setTag(KnownTags.URL_SCHEME_ID, "https");
    verify(span).setTag(KnownTags.Inbound.SERVER_ADDRESS_ID, "api.host");
    verify(span).setTag(KnownTags.Inbound.SERVER_PORT_ID, 443);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void inferredProxyOmitsUnknownOrigin(String domain) {
    HttpServerUrl url = HttpServerUrl.fromInferredProxy(domain, "/a");
    AgentSpan datadog = mock(AgentSpan.class);

    DatadogServerUrl.URL_ONLY.extract(url, datadog);
    OtelServerUrl.INSTANCE.extract(url, span);

    verify(datadog).setTag(KnownTags.HTTP_URL_ID, (CharSequence) "/a");
    verify(span).setTag(KnownTags.URL_PATH_ID, "/a");
    verify(span, never()).setTag(eq(KnownTags.URL_SCHEME_ID), anyString());
    verify(span, never()).setTag(eq(KnownTags.Inbound.SERVER_ADDRESS_ID), anyString());
    verify(span, never()).setTag(eq(KnownTags.Inbound.SERVER_PORT_ID), anyInt());
  }

  private void extractServerUrl(
      String forwardedHost, String forwardedPort, String scheme, String host, int port) {
    URIDataAdapter uri = mock(URIDataAdapter.class);
    when(uri.isValid()).thenReturn(true);
    when(uri.scheme()).thenReturn(scheme);
    when(uri.host()).thenReturn(host);
    when(uri.port()).thenReturn(port);
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    when(extracted.getXForwardedHost()).thenReturn(forwardedHost);
    when(extracted.getXForwardedPort()).thenReturn(forwardedPort);
    OtelServerUrl.INSTANCE.extract(HttpServerUrl.fromRequest(uri, "/", extracted, null), span);
  }
}
