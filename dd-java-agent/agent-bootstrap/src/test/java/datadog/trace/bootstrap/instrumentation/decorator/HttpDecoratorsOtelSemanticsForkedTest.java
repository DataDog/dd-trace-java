package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.context.Context.root;
import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    assertEquals("HTTP", span.getResourceName().toString());
    assertEquals("_OTHER", span.getTag(KnownTags.HTTP_METHOD_OTEL_NAME));
    assertEquals("BREW", span.getTag(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME));
    assertEquals(
        "https://REDACTED:REDACTED@example.com/orders#fragment",
        span.getTag(KnownTags.HTTP_URL_OTEL_NAME).toString());
    assertEquals("token=secret", span.getTag(KnownTags.URL_QUERY_NAME));
    assertEquals("example.com", span.getTag(KnownTags.SERVER_ADDRESS_NAME));
    assertEquals(443, span.getTag(KnownTags.SERVER_PORT_NAME));
    assertEquals(503, span.getHttpStatusCode());
    assertEquals("503", span.getTag("error.type"));
    assertTrue(span.isError());
    assertNull(span.getTag("peer.hostname"));
    assertNull(span.getTag("peer.port"));
  }

  @Test
  void urlConnectionDecoratorCapturesQueryForObfuscation() throws Exception {
    DDSpan span = (DDSpan) tracer.startSpan("test", "urlconnection.request");

    UrlConnectionDecorator.DECORATE.onURI(
        span, new URI("https://example.com/orders?token=secret#fragment"));

    assertEquals("https://example.com/orders#fragment", span.getTag(KnownTags.HTTP_URL_OTEL_NAME));
    assertEquals("token=secret", span.getTag(KnownTags.URL_QUERY_NAME));
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

    assertEquals("GET /users/{id}", span.getResourceName().toString());
    assertEquals("GET", span.getTag(KnownTags.HTTP_METHOD_OTEL_NAME));
    assertEquals("/users/123", span.getTag(KnownTags.URL_PATH_NAME));
    assertEquals("https", span.getTag(KnownTags.URL_SCHEME_NAME));
    assertEquals("view=full", span.getTag(KnownTags.URL_QUERY_NAME));
    assertEquals("example.com", span.getTag(KnownTags.SERVER_ADDRESS_NAME));
    assertEquals(8443, span.getTag(KnownTags.SERVER_PORT_NAME));
    assertEquals("192.0.2.10", span.getTag(KnownTags.HTTP_CLIENT_IP_OTEL_NAME));
    assertEquals("192.0.2.10", span.getTag(KnownTags.NETWORK_PEER_ADDRESS_NAME));
    assertEquals(503, span.getHttpStatusCode());
    assertEquals("503", span.getTag("error.type"));
    assertTrue(span.isError());
    assertFalse(span.getTags().containsKey("http.url"));
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
    assertEquals("public.example.com", span.getTag(KnownTags.SERVER_ADDRESS_NAME));
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
    assertEquals("2001:db8::1", span.getTag(KnownTags.SERVER_ADDRESS_NAME));
    assertEquals(9443, span.getTag(KnownTags.SERVER_PORT_NAME));
  }

  private static final class ClientRequest {
    private final String method;
    private final URI uri;

    private ClientRequest(String method, URI uri) {
      this.method = method;
      this.uri = uri;
    }
  }

  private static final class TestClientDecorator
      extends HttpClientDecorator<ClientRequest, Integer> {
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
    private final URI uri;

    private ServerRequest(String method, URI uri) {
      this.method = method;
      this.uri = uri;
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
      return new URIDefaultDataAdapter(request.uri);
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
