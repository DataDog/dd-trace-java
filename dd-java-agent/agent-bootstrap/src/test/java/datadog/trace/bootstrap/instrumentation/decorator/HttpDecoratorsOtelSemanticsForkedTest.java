package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.context.Context.root;
import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.config.TracerConfig.CLIENT_IP_ENABLED;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_PATH_NORMALIZER;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.MANUAL_INSTRUMENTATION;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.core.CoreSpan.OWN_PROPAGATION_TAGS;
import static datadog.trace.core.MetadataConsumer.NO_OP;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import datadog.appsec.api.blocking.BlockingException;
import datadog.json.JsonMapper;
import datadog.json.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.KnownTags;
import datadog.trace.api.gateway.InferredProxySpan;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UnparseableURIDataAdapter;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.trace.OtlpTraceJson;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@WithConfig(key = TRACE_OTEL_SEMANTICS_ENABLED, value = "true")
@WithConfig(key = HTTP_CLIENT_TAG_QUERY_STRING, value = "false")
@WithConfig(key = HTTP_SERVER_TAG_QUERY_STRING, value = "false")
class HttpDecoratorsOtelSemanticsForkedTest {
  private CoreTracer tracer;

  @BeforeEach
  void setUp() {
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  @Test
  void clientDecoratorCapturesOtelHttpValues() throws Exception {
    TestClientDecorator decorator = new TestClientDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    ClientRequest request =
        new ClientRequest(
            "BREW", new URI("https://user:password@example.com/orders?token=secret#fragment"));

    decorator.afterStart(span);
    decorator.onRequest(span, request);
    decorator.onResponse(span, 503);
    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals("HTTP", span.getResourceName().toString());
    assertEquals("_OTHER", span.getTag(KnownTags.HTTP_METHOD_OTEL_NAME));
    assertEquals("BREW", span.getTag(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME));
    assertEquals(
        "https://REDACTED:REDACTED@example.com/orders#fragment",
        span.getTag(KnownTags.HTTP_URL_OTEL_NAME).toString());
    assertEquals("example.com", span.getTag(Tags.PEER_HOSTNAME));
    assertEquals(443, span.getTag(Tags.PEER_PORT));
    assertEquals(503, span.getHttpStatusCode());
    assertEquals("503", span.getTag("error.type"));
    assertTrue(span.isError());
  }

  @TableTest({
    "Scenario               | Method | URL                        | Span type | Expected resource",
    "No URL                 | GET    |                            | http      | GET              ",
    "Extension method       | BREW   |                            | http      | HTTP             ",
    "Missing method         |        |                            | http      | HTTP             ",
    "Opaque URL             | GET    | mailto:test@test.com       | http      | GET              ",
    "Other span type        | GET    |                            | custom    | GET              ",
    "Missing span type      | GET    |                            |           | GET              ",
    "HTTP client path       | GET    | https://example.com/orders | http      | GET              ",
    "Other span type path   | GET    | https://example.com/orders | custom    | GET /orders      ",
    "Missing span type path | GET    | https://example.com/orders |           | GET /orders      "
  })
  void clientResourceNamePreservesSpanTypeBehavior(
      String method, String url, String spanType, String expectedResource) {
    TestClientDecorator decorator = new TestClientDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");

    decorator.afterStart(span);
    span.setSpanType(spanType);
    decorator.onRequest(span, new ClientRequest(method, url == null ? null : URI.create(url)));

    assertEquals(expectedResource, span.getResourceName().toString());
    assertEquals(HTTP_PATH_NORMALIZER, span.getResourceNamePriority());
  }

  @TableTest({
    "Scenario             | Fail extraction",
    "URL extraction fails | true           ",
    "URL decoration fails | false          "
  })
  void clientResourceNameSurvivesUrlFailures(boolean failExtraction) {
    TestClientDecorator decorator = spy(new TestClientDecorator());
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    URI uri = URI.create("https://example.com/orders");
    ClientRequest request = new ClientRequest("GET", uri);
    RuntimeException failure = new IllegalArgumentException("Invalid URL");
    if (failExtraction) {
      doThrow(failure).when(decorator).url(request);
    } else {
      doThrow(failure).when(decorator).onURI(span, uri);
    }

    decorator.afterStart(span);
    decorator.onRequest(span, request);

    assertEquals("GET", span.getResourceName().toString());
    assertEquals(HTTP_PATH_NORMALIZER, span.getResourceNamePriority());
  }

  @Test
  void clientResourceNameSurvivesBlockingException() {
    TestClientDecorator decorator = spy(new TestClientDecorator());
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    URI uri = URI.create("https://example.com/orders");
    ClientRequest request = new ClientRequest("GET", uri);
    BlockingException failure = new BlockingException("Blocked request");
    doThrow(failure).when(decorator).onURI(span, uri);

    decorator.afterStart(span);

    assertSame(
        failure, assertThrows(BlockingException.class, () -> decorator.onRequest(span, request)));
    assertEquals("GET", span.getResourceName().toString());
    assertEquals(HTTP_PATH_NORMALIZER, span.getResourceNamePriority());
  }

  @Test
  void clientResourceNameWithoutUrlPreservesManualName() {
    TestClientDecorator decorator = new TestClientDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    span.setResourceName("custom operation", MANUAL_INSTRUMENTATION);

    decorator.afterStart(span);
    decorator.onRequest(span, new ClientRequest("GET", null));

    assertEquals("custom operation", span.getResourceName().toString());
    assertEquals(MANUAL_INSTRUMENTATION, span.getResourceNamePriority());
  }

  @TableTest({
    "Scenario | URL                       ",
    "No URL   |                           ",
    "With URL | https://example.com/orders"
  })
  @WithConfig(key = "trace.URLAsResourceNameRule.enabled", value = "false")
  void clientResourceNamingCanBeDisabled(String url) {
    TestClientDecorator decorator = spy(new TestClientDecorator());
    doReturn(false).when(decorator).shouldSetResourceName();
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    span.setResourceName("custom operation");

    decorator.afterStart(span);
    decorator.onRequest(span, new ClientRequest("GET", url == null ? null : URI.create(url)));

    assertEquals("custom operation", span.getResourceName().toString());
  }

  @Test
  void urlConnectionDecoratorRespectsQueryStringOptOut() throws Exception {
    DDSpan span = (DDSpan) tracer.startSpan("test", "urlconnection.request");

    UrlConnectionDecorator.DECORATE.afterStart(span);
    UrlConnectionDecorator.DECORATE.onURI(
        span, new URI("https://example.com/orders?token=secret#fragment"));
    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals(
        "https://example.com/orders#fragment",
        span.getTag(KnownTags.HTTP_URL_OTEL_NAME).toString());
  }

  @Test
  @WithConfig(key = HTTP_CLIENT_TAG_QUERY_STRING, value = "true")
  void clientDecoratorCapturesAndObfuscatesQueryWhenEnabled() throws Exception {
    TestClientDecorator decorator = new TestClientDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    URI uri = new URI("https://user:password@example.com/orders?token=secret#fragment");

    decorator.afterStart(span);
    decorator.onRequest(span, new ClientRequest("GET", uri));

    assertEquals("token=secret", span.getTag(KnownTags.URL_QUERY_NAME));

    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals(
        "https://REDACTED:REDACTED@example.com/orders?<redacted>#fragment",
        span.getTag(KnownTags.HTTP_URL_OTEL_NAME).toString());
  }

  @Test
  @WithConfig(key = HTTP_CLIENT_TAG_QUERY_STRING, value = "true")
  void urlConnectionDecoratorCapturesAndObfuscatesQueryWhenEnabled() throws Exception {
    DDSpan span = (DDSpan) tracer.startSpan("test", "urlconnection.request");
    URI uri = new URI("https://user:password@example.com/orders?token=secret#fragment");

    UrlConnectionDecorator.DECORATE.afterStart(span);
    UrlConnectionDecorator.DECORATE.onURI(span, uri);

    assertEquals("token=secret", span.getTag(KnownTags.URL_QUERY_NAME));

    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals(
        "https://REDACTED:REDACTED@example.com/orders?<redacted>#fragment",
        span.getTag(KnownTags.HTTP_URL_OTEL_NAME).toString());
  }

  @Test
  void serverDecoratorCapturesOtelHttpValuesAndRouteName() throws Exception {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request =
        new ServerRequest("GET", new URI("https://example.com:8443/users/123?view=full"));

    decorator.afterStart(span);
    decorator.onRequest(span, new Connection("192.0.2.10", 32100), request, root());
    HTTP_RESOURCE_DECORATOR.withRoute(span, request.method, "/users/{id}");
    decorator.onResponse(span, 503);
    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals("GET /users/{id}", span.getResourceName().toString());
    assertEquals("GET", span.getTag(KnownTags.HTTP_METHOD_OTEL_NAME));
    assertEquals("/users/123", span.getTag(KnownTags.URL_PATH_NAME));
    assertEquals("https", span.getTag(KnownTags.URL_SCHEME_NAME));
    assertNull(span.getTag(KnownTags.URL_QUERY_NAME));
    assertEquals("example.com", span.getTag(KnownTags.HTTP_HOSTNAME_NAME));
    assertEquals(8443, span.getTag(KnownTags.SERVER_PORT_NAME));
    assertNull(span.getTag(Tags.HTTP_CLIENT_IP));
    assertNull(span.getTag(Tags.NETWORK_CLIENT_IP));
    assertEquals(503, span.getHttpStatusCode());
    assertEquals("503", span.getTag("error.type"));
    assertTrue(span.isError());
    assertFalse(span.getTags().containsKey("http.url"));
  }

  @Test
  @WithConfig(key = HTTP_SERVER_TAG_QUERY_STRING, value = "true")
  void serverDecoratorCapturesAndObfuscatesQueryWhenEnabled() throws Exception {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request =
        new ServerRequest("GET", new URI("https://example.com/orders?token=secret&view=full"));

    decorator.afterStart(span);
    decorator.onRequest(span, new Connection("192.0.2.10", 32100), request, root());

    assertEquals("token=secret&view=full", span.getTag(KnownTags.URL_QUERY_NAME));

    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals("<redacted>&view=full", span.getTag(KnownTags.URL_QUERY_NAME));
    assertEquals("/orders", span.getTag(KnownTags.URL_PATH_NAME));
  }

  @TableTest({
    "Scenario             | Request URI                                            | Expected Path",
    "query                | /bad%path?token=secret&view=full                       | /bad%path    ",
    "encoded marker       | /bad%3F%path?token=secret                              | /bad%3F%path ",
    "query and fragment   | /bad%path?token=secret&view=full#fragment              | /bad%path    ",
    "fragment only        | /bad%path#fragment                                     | /bad%path    ",
    "fragment question    | /bad%path#fragment?token=secret                        | /bad%path    ",
    "empty query fragment | /bad%path?#fragment                                    | /bad%path    ",
    "encoded markers      | /bad%23%path?token=secret&view=full%23details#fragment | /bad%23%path ",
    "empty query          | /bad%path?                                             | /bad%path    ",
    "no query             | /bad%path                                              | /bad%path    "
  })
  void invalidServerUrlRespectsQueryStringOptOut(String requestUri, String expectedPath) {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request =
        new ServerRequest("GET", new UnparseableURIDataAdapter(requestUri), null);

    decorator.afterStart(span);
    decorator.onRequest(span, new Connection("192.0.2.10", 32100), request, root());
    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals(expectedPath, span.getTag(KnownTags.URL_PATH_NAME));
    assertNull(span.getTag(KnownTags.URL_QUERY_NAME));
    assertNull(span.getTag(Tags.HTTP_URL));
  }

  @TableTest({
    "Scenario             | Request URI                                            | Expected Path | Expected Query                   | Obfuscated Query              ",
    "malformed path       | /bad%path?token=secret&view=full                       | /bad%path     | token=secret&view=full           | <redacted>&view=full          ",
    "malformed query      | /orders?token=secret&view=%GG                          | /orders       | token=secret&view=%GG            | <redacted>&view=%GG           ",
    "extra marker         | /bad%path?token=secret&view=full?details               | /bad%path     | token=secret&view=full?details   | <redacted>&view=full?details  ",
    "query and fragment   | /bad%path?token=secret&view=full#fragment              | /bad%path     | token=secret&view=full           | <redacted>&view=full          ",
    "fragment only        | /bad%path#fragment                                     | /bad%path     |                                  |                               ",
    "fragment question    | /bad%path#fragment?token=secret                        | /bad%path     |                                  |                               ",
    "empty query fragment | /bad%path?#fragment                                    | /bad%path     |                                  |                               ",
    "encoded markers      | /bad%23%path?token=secret&view=full%23details#fragment | /bad%23%path  | token=secret&view=full%23details | <redacted>&view=full%23details",
    "empty query          | /bad%path?                                             | /bad%path     |                                  |                               ",
    "no query             | /bad%path                                              | /bad%path     |                                  |                               "
  })
  @WithConfig(key = HTTP_SERVER_TAG_QUERY_STRING, value = "true")
  void invalidServerUrlSeparatesAndObfuscatesQueryWhenEnabled(
      String requestUri, String expectedPath, String expectedQuery, String obfuscatedQuery) {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request =
        new ServerRequest("GET", new UnparseableURIDataAdapter(requestUri), null);

    decorator.afterStart(span);
    decorator.onRequest(span, new Connection("192.0.2.10", 32100), request, root());

    assertEquals(expectedPath, span.getTag(KnownTags.URL_PATH_NAME));
    assertEquals(expectedQuery, span.getTag(KnownTags.URL_QUERY_NAME));

    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals(expectedPath, span.getTag(KnownTags.URL_PATH_NAME));
    assertEquals(obfuscatedQuery, span.getTag(KnownTags.URL_QUERY_NAME));
    assertNull(span.getTag(Tags.HTTP_URL));
  }

  @Test
  @WithConfig(key = HTTP_SERVER_TAG_QUERY_STRING, value = "true")
  @WithConfig(key = HTTP_SERVER_RAW_QUERY_STRING, value = "false")
  void invalidServerUrlDecodesQueryWhenConfigured() {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request =
        new ServerRequest(
            "GET",
            new UnparseableURIDataAdapter(
                "/bad%path?t%6fken=secret&view=full%3Fdetails%23section#fragment?token=fragment-secret"),
            null);

    decorator.afterStart(span);
    decorator.onRequest(span, new Connection("192.0.2.10", 32100), request, root());

    assertEquals("token=secret&view=full?details%23section", span.getTag(KnownTags.URL_QUERY_NAME));

    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals("/bad%path", span.getTag(KnownTags.URL_PATH_NAME));
    assertEquals("<redacted>&view=full?details%23section", span.getTag(KnownTags.URL_QUERY_NAME));
  }

  @Test
  void serverDecoratorOmitsClientIpsAndForwardedHeadersByDefault() throws Exception {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request = new ServerRequest("GET", new URI("https://example.com/"));
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    headers.xForwardedFor = "203.0.113.7";
    headers.forwarded = "for=203.0.113.7";
    TagContext extracted = new TagContext(null, null, headers, null, 0, null, null, DDTraceId.ZERO);

    decorator.onRequest(
        span, new Connection("192.0.2.10", 32100), request, AgentSpan.fromSpanContext(extracted));

    assertNull(span.getTag(Tags.HTTP_CLIENT_IP));
    assertNull(span.getTag(Tags.NETWORK_CLIENT_IP));
    assertNull(span.getTag(Tags.HTTP_FORWARDED_IP));
    assertNull(span.getTag(Tags.HTTP_FORWARDED));
  }

  @TableTest({
    "Scenario          | Forwarded Ip | Expected Client Ip",
    "peer address      |              | 192.0.2.10        ",
    "forwarded address | 203.0.113.7  | 203.0.113.7       "
  })
  @WithConfig(key = CLIENT_IP_ENABLED, value = "true")
  void serverDecoratorCapturesClientIpsWithOptIn(String forwardedIp, String expectedClientIp)
      throws Exception {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request = new ServerRequest("GET", new URI("https://example.com/"));
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    headers.xForwardedFor = forwardedIp;
    headers.forwarded = forwardedIp == null ? null : "for=" + forwardedIp;
    TagContext extracted = new TagContext(null, null, headers, null, 0, null, null, DDTraceId.ZERO);

    decorator.onRequest(
        span,
        new Connection("192.0.2.10", 32100),
        request,
        forwardedIp == null ? root() : AgentSpan.fromSpanContext(extracted));

    assertEquals(expectedClientIp, span.getTag(KnownTags.HTTP_CLIENT_IP_OTEL_NAME));
    assertEquals("192.0.2.10", span.getTag(Tags.NETWORK_CLIENT_IP));
    assertEquals(forwardedIp, span.getTag(Tags.HTTP_FORWARDED_IP));
    assertEquals(headers.forwarded, span.getTag(Tags.HTTP_FORWARDED));
  }

  @TableTest({
    "Scenario      | Root Client Ip | Expected Client Ip",
    "root address  | 203.0.113.7    | 203.0.113.7       ",
    "peer fallback |                | 192.0.2.10        "
  })
  @WithConfig(key = CLIENT_IP_ENABLED, value = "true")
  void serverDecoratorInheritsClientIpFromLocalRoot(String rootClientIp, String expectedClientIp) {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan rootSpan = (DDSpan) tracer.startSpan("test", "servlet.request");
    rootSpan.setTag(Tags.HTTP_CLIENT_IP, rootClientIp);
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.dispatch", rootSpan.spanContext());
    ServerRequest request = new ServerRequest("GET", URI.create("https://example.com/"));

    decorator.onRequest(span, new Connection("192.0.2.10", 32100), request, root());

    assertEquals(expectedClientIp, span.getTag(Tags.HTTP_CLIENT_IP));
    assertEquals("192.0.2.10", span.getTag(Tags.NETWORK_CLIENT_IP));
    assertEquals(rootClientIp, rootSpan.getTag(Tags.HTTP_CLIENT_IP));
  }

  @Test
  @WithConfig(key = CLIENT_IP_ENABLED, value = "true")
  void serverDecoratorPreservesExistingClientIpAcrossRepeatedDecoration() {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    span.setTag(Tags.HTTP_CLIENT_IP, "203.0.113.7");
    ServerRequest request = new ServerRequest("GET", URI.create("https://example.com/"));

    decorator.onRequest(span, new Connection("192.0.2.10", 32100), request, root());

    assertEquals("203.0.113.7", span.getTag(Tags.HTTP_CLIENT_IP));

    decorator.onRequest(span, new Connection("192.0.2.11", 32101), request, root());

    assertEquals("203.0.113.7", span.getTag(Tags.HTTP_CLIENT_IP));
    assertEquals("192.0.2.11", span.getTag(Tags.NETWORK_CLIENT_IP));
  }

  @TableTest({
    "Scenario      | Request URI | Expected Path",
    "relative path | orders      | /orders      ",
    "empty path    | ''          | /            ",
    "absolute path | /orders     | /orders      "
  })
  void serverDecoratorNormalizesUrlPath(String requestUri, String expectedPath) {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request = new ServerRequest("GET", URI.create(requestUri));

    decorator.onRequest(span, new Connection("192.0.2.10", 32100), request, root());

    assertEquals(expectedPath, span.getTag(KnownTags.URL_PATH_NAME));
  }

  @Test
  void serverDecoratorUsesForwardedOrigin() throws Exception {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request =
        new ServerRequest("GET", new URI("http://internal.example.com:8080/users/123"));
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    headers.xForwardedProto = "https, http";
    headers.xForwardedHost = "public.example.com:8443, internal.example.com:8080";
    TagContext extracted = new TagContext(null, null, headers, null, 0, null, null, DDTraceId.ZERO);

    decorator.afterStart(span);
    decorator.onRequest(
        span, new Connection("192.0.2.10", 32100), request, AgentSpan.fromSpanContext(extracted));

    assertEquals("https", span.getTag(KnownTags.URL_SCHEME_NAME));
    assertEquals("public.example.com", span.getTag(KnownTags.HTTP_HOSTNAME_NAME));
    assertEquals(8443, span.getTag(KnownTags.SERVER_PORT_NAME));
  }

  @Test
  void serverDecoratorUsesForwardedPortWithIpv6Host() throws Exception {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request =
        new ServerRequest("GET", new URI("http://internal.example.com:8080/users/123"));
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    headers.xForwardedProto = "https";
    headers.xForwardedHost = "[2001:db8::1]";
    headers.xForwardedPort = "9443";
    TagContext extracted = new TagContext(null, null, headers, null, 0, null, null, DDTraceId.ZERO);

    decorator.afterStart(span);
    decorator.onRequest(
        span, new Connection("192.0.2.10", 32100), request, AgentSpan.fromSpanContext(extracted));

    assertEquals("https", span.getTag(KnownTags.URL_SCHEME_NAME));
    assertEquals("2001:db8::1", span.getTag(KnownTags.HTTP_HOSTNAME_NAME));
    assertEquals(9443, span.getTag(KnownTags.SERVER_PORT_NAME));
  }

  @TableTest({
    "Scenario             | Request URI                             | Host header             | Expected port",
    "Backend default port | http://internal.example.com:80/orders   | public.example.com      | 443          ",
    "Backend custom port  | http://internal.example.com:8080/orders | public.example.com      | 443          ",
    "Missing Host header  | http://internal.example.com:8080/orders |                         | 443          ",
    "Explicit public port | http://internal.example.com:8080/orders | public.example.com:9443 | 9443         ",
    "Explicit IPv6 port   | http://internal.example.com:8080/orders | '[2001:db8::1]:8443'    | 8443         ",
    "Already resolved     | https://public.example.com:9443/orders  | public.example.com      | 9443         "
  })
  void serverDecoratorUsesOriginPortWithOnlyForwardedScheme(
      String requestUri, String hostHeader, int expectedPort) {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request");
    ServerRequest request = new ServerRequest("GET", URI.create(requestUri), hostHeader);
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    headers.xForwardedProto = "https, http";
    TagContext extracted = new TagContext(null, null, headers, null, 0, null, null, DDTraceId.ZERO);

    decorator.afterStart(span);
    decorator.onRequest(
        span, new Connection("192.0.2.10", 32100), request, AgentSpan.fromSpanContext(extracted));

    assertEquals("https", span.getTag(KnownTags.URL_SCHEME_NAME));
    assertEquals(expectedPort, span.getTag(KnownTags.SERVER_PORT_NAME));
  }

  @ParameterizedTest
  @ValueSource(strings = {"elasticsearch", "opensearch"})
  void databaseRestClientsKeepEndpointResourceNames(String spanType) {
    DDSpan span = (DDSpan) tracer.startSpan("test", "database.query");
    span.setSpanType(spanType);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    // Both REST client decorators use this helper to name their database spans.
    HTTP_RESOURCE_DECORATOR.withClientPath(span, "GET", "/index/_search");
    span.finish();

    assertEquals("GET /index/_search", span.getResourceName().toString());
  }

  @TableTest({
    "Scenario      | URL                             | Expected port",
    "HTTP default  | http://example.com/orders       | 80           ",
    "HTTPS default | https://example.com/orders      | 443          ",
    "Explicit port | https://example.com:8443/orders | 8443         "
  })
  void clientDestinationPortUsesPeerIdentity(String url, int expectedPort) {
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    TestClientDecorator decorator = new TestClientDecorator();

    decorator.afterStart(span);
    decorator.onRequest(span, new ClientRequest("GET", URI.create(url)));
    span.finish();

    assertEquals(expectedPort, span.getTag(Tags.PEER_PORT));
  }

  @Test
  void clientConnectionDoesNotExportDuplicateServerPorts() throws IOException {
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    TestClientDecorator decorator = new TestClientDecorator();
    decorator.afterStart(span);
    decorator.onRequest(
        span, new ClientRequest("GET", URI.create("https://example.com:8443/orders")));
    // Netty decorates the connection after the request, including when it connects to a proxy.
    decorator.onPeerConnection(span, InetSocketAddress.createUnresolved("proxy.example.com", 3128));
    span.finish();

    List<Object> ports = exportedAttributeValues(span, "server.port");

    assertEquals(singletonList(String.valueOf(span.getTag(Tags.PEER_PORT))), ports);
  }

  /** Decodes parameter names while preserving encoded delimiters for complete redaction. */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @WithConfig(key = HTTP_CLIENT_TAG_QUERY_STRING, value = "true")
  void clientQueriesAreDecodedBeforeObfuscating(boolean urlConnection) {
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    URI uri =
        URI.create(
            "https://user:password@example.com/orders"
                + "?t%6fken=first%26SECOND_HALF%23LAST&view=a%26b%3Dc&space=hello%20world&plus=%2B#fragment");

    if (urlConnection) {
      UrlConnectionDecorator.DECORATE.afterStart(span);
      UrlConnectionDecorator.DECORATE.onURI(span, uri);
    } else {
      TestClientDecorator decorator = new TestClientDecorator();
      decorator.afterStart(span);
      decorator.onRequest(span, new ClientRequest("GET", uri));
    }
    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    // Redact the entire encoded secret and preserve boundaries in safe values.
    assertEquals(
        "https://REDACTED:REDACTED@example.com/orders"
            + "?<redacted>&view=a%26b=c&space=hello world&plus=+#fragment",
        span.getTag(KnownTags.HTTP_URL_OTEL_NAME).toString());
  }

  @TableTest({
    "Scenario        | Propagation headers                                                     ",
    "Datadog headers | [x-datadog-trace-id: '1', x-datadog-parent-id: '2']                     ",
    "W3C headers     | [traceparent: '00-00000000000000000000000000000001-0000000000000002-01']"
  })
  @WithConfig(key = "appsec.enabled", value = "false")
  @WithConfig(key = "ai_guard.enabled", value = "false")
  @WithConfig(key = CLIENT_IP_ENABLED, value = "false")
  @WithConfig(key = "trace.propagation.style.extract", value = "datadog,tracecontext")
  void serverOriginUsesHeadersFromRealPropagationWithoutIpOptIn(
      Map<String, String> propagationHeaders) {
    Map<String, String> headers = new HashMap<>(propagationHeaders);
    headers.put("x-forwarded-proto", "https, http");
    headers.put("x-forwarded-host", "public.example.com:8443, internal.example.com:8080");
    headers.put("x-forwarded-port", "9443, 8080");
    headers.put("x-forwarded-for", "203.0.113.7");
    boolean originalAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = false;
    HttpCodec.Extractor extractor =
        HttpCodec.createExtractor(Config.get(), tracer::captureTraceConfig);
    try {
      TagContext extracted = extractor.extract(headers, stringValuesMap());
      assertNotNull(extracted);
      assertNull(extracted.getXForwardedFor());
      DDSpan span = (DDSpan) tracer.startSpan("test", "servlet.request", extracted);
      TestServerDecorator decorator = new TestServerDecorator();

      decorator.afterStart(span);
      decorator.onRequest(
          span,
          new Connection("192.0.2.10", 32100),
          new ServerRequest("GET", URI.create("http://internal.example.com:8080/orders")),
          AgentSpan.fromSpanContext(extracted));
      span.finish();

      assertEquals("https", span.getTag(KnownTags.URL_SCHEME_NAME));
      assertEquals("public.example.com", span.getTag(KnownTags.HTTP_HOSTNAME_NAME));
      assertEquals(9443, span.getTag(KnownTags.SERVER_PORT_NAME));
      assertNull(span.getTag(KnownTags.HTTP_CLIENT_IP_OTEL_NAME));
      assertNull(span.getTag(Tags.NETWORK_CLIENT_IP));
      assertNull(span.getTag(Tags.HTTP_FORWARDED_IP));
    } finally {
      extractor.cleanup();
      ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive;
    }
  }

  @TableTest({
    "Scenario                 | Proxy system   | Domain          | Expected port",
    "API Gateway HTTPS        | aws-apigateway | api.example.com | 443          ",
    "HTTP API HTTPS           | aws-httpapi    | api.example.com | 443          ",
    "API Gateway without host | aws-apigateway |                 |              ",
    "HTTP API without host    | aws-httpapi    |                 |              "
  })
  void inferredProxyUsesKnownHttpsPort(String proxySystem, String domain, Integer expectedPort) {
    Map<String, String> headers = new HashMap<>();
    headers.put("x-dd-proxy", proxySystem);
    headers.put("x-dd-proxy-request-time-ms", Long.toString(System.currentTimeMillis()));
    headers.put("x-dd-proxy-httpmethod", "GET");
    headers.put("x-dd-proxy-path", "/orders");
    if (domain != null) {
      headers.put("x-dd-proxy-domain-name", domain);
    }
    InferredProxySpan inferred = InferredProxySpan.fromHeaders(headers);
    AgentTracer.TracerAPI originalTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracer);
    try {
      AgentSpanContext parent = inferred.start(null);
      assertNotNull(parent);
      DDSpan child = (DDSpan) tracer.startSpan("test", "servlet.request", parent);
      AgentSpan proxy = child.getLocalRootSpan();
      child.finish();

      assertEquals(domain, proxy.getTag(KnownTags.HTTP_HOSTNAME_NAME));
      assertEquals(domain == null ? null : "https", proxy.getTag(KnownTags.URL_SCHEME_NAME));
      assertEquals(expectedPort, proxy.getTag(KnownTags.SERVER_PORT_NAME));
    } finally {
      inferred.finish();
      AgentTracer.forceRegister(originalTracer);
    }
  }

  private static List<Object> exportedAttributeValues(DDSpan span, String key) throws IOException {
    Map<String, Object> exportedSpan;
    try (JsonWriter writer = new JsonWriter()) {
      OtlpTraceJson.writeSpan(writer, span, new OtlpTraceJson.MetaWriter(writer), emptyList());
      exportedSpan = JsonMapper.fromJsonToMap(writer.toString());
    }
    List<Object> values = new ArrayList<>();
    for (Object item : (List<?>) exportedSpan.get("attributes")) {
      Map<?, ?> attribute = (Map<?, ?>) item;
      if (key.equals(attribute.get("key"))) {
        values.add(((Map<?, ?>) attribute.get("value")).values().iterator().next());
      }
    }
    return values;
  }

  static final class ClientRequest {
    private final String method;
    private final URI uri;

    ClientRequest(String method, URI uri) {
      this.method = method;
      this.uri = uri;
    }
  }

  static class TestClientDecorator extends HttpClientDecorator<ClientRequest, Integer> {
    @Override
    protected String[] instrumentationNames() {
      return new String[] {"test-http-client"};
    }

    @Override
    protected CharSequence component() {
      return "test-http-client";
    }

    @Override
    protected String method(ClientRequest request) {
      return request.method;
    }

    @Override
    protected URI url(ClientRequest request) {
      return request.uri;
    }

    @Override
    protected int status(Integer response) {
      return response;
    }

    @Override
    protected String getRequestHeader(ClientRequest request, String headerName) {
      return null;
    }

    @Override
    protected String getResponseHeader(Integer response, String headerName) {
      return null;
    }
  }

  private static final class ServerRequest {
    private final String method;
    private final URIDataAdapter uri;
    private final String hostHeader;

    private ServerRequest(String method, URI uri) {
      this(method, uri, null);
    }

    private ServerRequest(String method, URI uri, String hostHeader) {
      this(method, new URIDefaultDataAdapter(uri), hostHeader);
    }

    private ServerRequest(String method, URIDataAdapter uri, String hostHeader) {
      this.method = method;
      this.uri = uri;
      this.hostHeader = hostHeader;
    }
  }

  private static final class Connection {
    private final String host;
    private final int port;

    private Connection(String host, int port) {
      this.host = host;
      this.port = port;
    }
  }

  private static final class TestServerDecorator
      extends HttpServerDecorator<ServerRequest, Connection, Integer, Object> {
    @Override
    protected String[] instrumentationNames() {
      return new String[] {"test-http-server"};
    }

    @Override
    protected CharSequence component() {
      return "test-http-server";
    }

    @Override
    protected AgentPropagation.ContextVisitor<Object> getter() {
      return null;
    }

    @Override
    protected AgentPropagation.ContextVisitor<Integer> responseGetter() {
      return null;
    }

    @Override
    public CharSequence spanName() {
      return "http.request";
    }

    @Override
    protected String method(ServerRequest request) {
      return request.method;
    }

    @Override
    protected URIDataAdapter url(ServerRequest request) {
      return request.uri;
    }

    @Override
    protected String getRequestHeader(ServerRequest request, String key) {
      return "Host".equalsIgnoreCase(key) ? request.hostHeader : null;
    }

    @Override
    protected String peerHostIP(Connection connection) {
      return connection.host;
    }

    @Override
    protected int peerPort(Connection connection) {
      return connection.port;
    }

    @Override
    protected int status(Integer response) {
      return response;
    }
  }
}
