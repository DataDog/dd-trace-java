package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.lang.reflect.Field;
import java.net.URI;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;

@WithConfig(key = "http.client.tag.query-string", value = "true")
@WithConfig(key = "http.server.tag.query-string", value = "true")
class HttpUrlExtractorsTest {
  private final AgentSpan span = mock(AgentSpan.class);

  @TableTest({
    "Scenario           | Get first",
    "Render as string   | false    ",
    "Render as supplier | true     "
  })
  void otelClientLazilyStripsQueryAndRedactsCredentials(boolean getFirst) throws Exception {
    ArgumentCaptor<LazyUrl> urlCaptor = ArgumentCaptor.forClass(LazyUrl.class);

    URI uri = URI.create("https://u:p@host/a?token=secret#fragment");
    OtelClientUrl.INSTANCE.extract(uri, span);

    verify(span).setTag(eq(KnownTags.HTTP_URL_ID), (Object) urlCaptor.capture());
    LazyUrl url = urlCaptor.getValue();

    // Reading the path must leave the full URL unrendered.
    assertEquals("/a", url.path());
    assertNull(url.lazy);
    Field source = url.getClass().getDeclaredField("uri");
    source.setAccessible(true);
    assertSame(uri, source.get(url));
    assertEquals(
        "https://REDACTED:REDACTED@host/a#fragment", getFirst ? url.get() : url.toString());
    assertNull(source.get(url));
    assertEquals("/a", url.path());
    assertEquals("https://REDACTED:REDACTED@host/a#fragment", url.toString());
    assertEquals("https://REDACTED:REDACTED@host/a#fragment", url.get());
    verify(span).setTag(KnownTags.URL_QUERY_ID, "token=secret");
  }

  @Test
  void otelClientSupportsConcurrentRenderingAndPathReads() throws Exception {
    ArgumentCaptor<LazyUrl> urlCaptor = ArgumentCaptor.forClass(LazyUrl.class);
    OtelClientUrl.INSTANCE.extract(URI.create("https://host/a%3Fb?token=secret#fragment"), span);
    verify(span).setTag(eq(KnownTags.HTTP_URL_ID), (Object) urlCaptor.capture());
    LazyUrl url = urlCaptor.getValue();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CyclicBarrier start = new CyclicBarrier(2);
    try {
      Future<?> rendering =
          executor.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 1000; i++) {
                  assertEquals("https://host/a%3Fb#fragment", url.get());
                  assertEquals("a%3Fb", url.subSequence(13, 18).toString());
                }
                return null;
              });
      Future<?> reading =
          executor.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 1000; i++) {
                  assertEquals("/a?b", url.path());
                  assertEquals("https://host/a%3Fb#fragment", url.toString());
                }
                return null;
              });
      rendering.get(10, TimeUnit.SECONDS);
      reading.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
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
    verify(span).setTag(KnownTags.URL_QUERY_ID, expectedQuery);
  }

  @TableTest({
    "Scenario          | Raw query                               | Expected query                       ",
    "Encoded ampersand | token=first%26LAST&view=full            | token=first%26LAST&view=full         ",
    "Encoded name      | t%6fken=first%26LAST&view=full          | token=first%26LAST&view=full         ",
    "Encoded fragment  | token=first%23LAST&view=full            | token=first%23LAST&view=full         ",
    "Safe delimiters   | q=one%26two%23three&space=hello%20world | q=one%26two%23three&space=hello world"
  })
  void clientsPreserveEncodedQueryBoundaries(String rawQuery, String expectedQuery) {
    URI uri = URI.create("http://host/orders?" + rawQuery);
    AgentSpan datadog = mock(AgentSpan.class);

    DatadogClientUrl.INSTANCE.extract(uri, datadog);
    OtelClientUrl.INSTANCE.extract(uri, span);

    verify(datadog).setTag(KnownTags.HTTP_QUERY_STRING_ID, expectedQuery);
    verify(span).setTag(KnownTags.URL_QUERY_ID, expectedQuery);
  }

  @TableTest({
    "Scenario          | Raw query                               | Expected query                       ",
    "Encoded ampersand | token=first%26LAST&view=full            | token=first%26LAST&view=full         ",
    "Encoded name      | t%6fken=first%26LAST&view=full          | token=first%26LAST&view=full         ",
    "Encoded fragment  | token=first%23LAST&view=full            | token=first%23LAST&view=full         ",
    "Safe delimiters   | q=one%26two%23three&space=hello%20world | q=one%26two%23three&space=hello world"
  })
  @WithConfig(key = "http.server.raw.query-string", value = "false")
  void serversPreserveEncodedQueryBoundaries(String rawQuery, String expectedQuery) {
    URI uri = URI.create("http://host/orders?" + rawQuery);
    HttpServerUrl url =
        HttpServerUrl.fromRequest(new URIDefaultDataAdapter(uri), uri.getPath(), null, null);
    AgentSpan datadog = mock(AgentSpan.class);

    DatadogServerUrl.INSTANCE.extract(url, datadog);
    OtelServerUrl.INSTANCE.extract(url, span);

    verify(datadog).setTag(KnownTags.HTTP_QUERY_STRING_ID, expectedQuery);
    verify(span).setTag(KnownTags.URL_QUERY_ID, expectedQuery);
  }

  @Test
  void otelClientOmitsQueryTagWhenAbsent() {
    OtelClientUrl.INSTANCE.extract(URI.create("http://host/a"), span);

    verify(span, never()).setTag(eq(KnownTags.URL_QUERY_ID), anyString());
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
  void otelClientRespectsQueryOptOut() {
    OtelClientUrl.INSTANCE.extract(URI.create("http://host/a?token=secret"), span);

    verify(span, never()).setTag(eq(KnownTags.URL_QUERY_ID), anyString());
  }

  @Test
  @WithConfig(key = "http.client.tag.query-string", value = "false")
  void datadogClientRespectsQueryOptOut() {
    DatadogClientUrl.INSTANCE.extract(URI.create("http://host/a?token=secret#fragment"), span);

    verify(span, never()).setTag(eq(KnownTags.HTTP_QUERY_STRING_ID), anyString());
    verify(span, never()).setTag(eq(DDTags.HTTP_FRAGMENT), anyString());
  }

  @TableTest({
    "Scenario      | Path | Expected path",
    "Missing path  |      | /            ",
    "Empty path    | ''   | /            ",
    "Relative path | a    | /a           ",
    "Absolute path | /a   | /a           "
  })
  void serverNormalizesRequestPath(String path, String expectedPath) {
    OtelServerUrl.INSTANCE.extract(
        HttpServerUrl.fromRequest(
            new URIDefaultDataAdapter(URI.create("http://host")), path, null, null),
        span);

    verify(span).setTag(KnownTags.URL_PATH_ID, expectedPath);
  }

  @TableTest({
    "Scenario    | Raw URL                    | Expected path | Expected query",
    "Query       | /bad%path?q=a%20b          | /bad%path     | q=a b         ",
    "Fragment    | /bad%path?q=a%20b#fragment | /bad%path     | q=a b         ",
    "No query    | /bad%path#fragment         | /bad%path     |               ",
    "Empty query | /bad%path?                 | /bad%path     |               "
  })
  @WithConfig(key = "http.server.raw.query-string", value = "false")
  void serverExtractsMalformedRequestUrl(String rawUrl, String expectedPath, String expectedQuery) {
    HttpServerUrl url =
        HttpServerUrl.fromRequest(new UnparseableURIDataAdapter(rawUrl), null, null, null);
    AgentSpan datadog = mock(AgentSpan.class);

    DatadogServerUrl.INSTANCE.extract(url, datadog);
    OtelServerUrl.INSTANCE.extract(url, span);

    ArgumentCaptor<CharSequence> captured = ArgumentCaptor.forClass(CharSequence.class);
    verify(datadog).setTag(eq(KnownTags.HTTP_URL_ID), captured.capture());
    assertEquals(rawUrl, captured.getValue().toString());
    verify(datadog, never()).setTag(eq(KnownTags.HTTP_QUERY_STRING_ID), anyString());
    verify(span).setTag(KnownTags.URL_PATH_ID, expectedPath);
    if (expectedQuery == null) {
      verify(span, never()).setTag(eq(KnownTags.URL_QUERY_ID), anyString());
    } else {
      verify(span).setTag(KnownTags.URL_QUERY_ID, expectedQuery);
    }
  }

  @Test
  @WithConfig(key = "http.server.raw.query-string", value = "true")
  void serverPreservesRawMalformedQuery() {
    OtelServerUrl.INSTANCE.extract(
        HttpServerUrl.fromRequest(
            new UnparseableURIDataAdapter("/bad%path?q=a%20b"), null, null, null),
        span);

    verify(span).setTag(KnownTags.URL_QUERY_ID, "q=a%20b");
  }

  @Test
  void serverOmitsUnavailableMalformedUrl() {
    URIDataAdapter adapter = mock(URIDataAdapter.class);
    HttpServerUrl url = HttpServerUrl.fromRequest(adapter, null, null, null);

    DatadogServerUrl.INSTANCE.extract(url, span);
    OtelServerUrl.INSTANCE.extract(url, span);

    verify(span, never()).setTag(eq(KnownTags.HTTP_URL_ID), anyString());
    verify(span, never()).setTag(eq(KnownTags.URL_PATH_ID), anyString());
    verify(span, never()).setTag(eq(KnownTags.URL_QUERY_ID), anyString());
  }

  @Test
  @WithConfig(key = "http.server.tag.query-string", value = "false")
  void datadogServerRespectsQueryOptOut() {
    DatadogServerUrl.INSTANCE.extract(
        HttpServerUrl.fromRequest(
            new URIDefaultDataAdapter(URI.create("http://host/a?q=1#fragment")), "/a", null, null),
        span);

    verify(span, never()).setTag(eq(KnownTags.HTTP_QUERY_STRING_ID), anyString());
    verify(span, never()).setTag(eq(DDTags.HTTP_FRAGMENT), anyString());
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
    verify(span).setTag(KnownTags.URL_QUERY_ID, "token=secret");
  }

  @Test
  @WithConfig(key = "http.server.tag.query-string", value = "false")
  void transactionRespectsQueryOptOut() {
    OtelServerUrl.INSTANCE.extract(
        HttpServerUrl.fromTransaction("https://host/a?token=secret", null, null), span);

    verify(span, never()).setTag(eq(KnownTags.URL_QUERY_ID), anyString());
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
