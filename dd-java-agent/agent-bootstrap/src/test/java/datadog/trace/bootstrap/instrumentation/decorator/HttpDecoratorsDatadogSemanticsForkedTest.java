package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN;
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.decorator.HttpDecoratorsOtelSemanticsForkedTest.TestClientDecorator;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = TRACE_OTEL_SEMANTICS_ENABLED, value = "false")
class HttpDecoratorsDatadogSemanticsForkedTest {
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
  @WithConfig(key = HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, value = "true")
  @WithConfig(key = SPLIT_BY_TAGS, value = "peer.port")
  void portServiceSplitTakesPrecedenceOverDomainSplit() {
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");

    new TestClientDecorator().onURI(span, URI.create("http://example.com:8080/orders"));
    span.finish();

    assertEquals("8080", span.getServiceName());
  }

  @Test
  void forwardedProtocolDoesNotTriggerHostHeaderLookup() {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    headers.xForwardedProto = "https";
    headers.xForwardedHost = "public.example.com";
    TagContext extracted = new TagContext(null, null, headers, null, 0, null, null, DDTraceId.ZERO);

    decorator.onRequest(
        span,
        null,
        URI.create("http://internal.example.com:8080/orders"),
        AgentSpan.fromSpanContext(extracted));
    span.finish();

    assertEquals(0, decorator.headerLookups);
    assertEquals("http://internal.example.com:8080/orders", span.getTag(Tags.HTTP_URL).toString());
    assertEquals("public.example.com", span.getTag(Tags.HTTP_HOSTNAME));
    assertNull(span.getTag("url.scheme"));
  }

  private static final class TestServerDecorator
      extends HttpServerDecorator<URI, Void, Integer, Object> {
    private int headerLookups;

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
    protected String method(URI request) {
      return "GET";
    }

    @Override
    protected URIDataAdapter url(URI request) {
      return new URIDefaultDataAdapter(request);
    }

    @Override
    protected String getRequestHeader(URI request, String key) {
      headerLookups++;
      return "Host".equalsIgnoreCase(key) ? "public.example.com" : null;
    }

    @Override
    protected String peerHostIP(Void connection) {
      return null;
    }

    @Override
    protected int peerPort(Void connection) {
      return 0;
    }

    @Override
    protected int status(Integer response) {
      return response;
    }
  }
}
