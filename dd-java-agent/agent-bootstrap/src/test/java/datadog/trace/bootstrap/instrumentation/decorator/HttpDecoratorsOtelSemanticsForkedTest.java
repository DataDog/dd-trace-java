package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.KnownTags;
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
}
