package com.datadog.appsec.gateway;

import static datadog.context.Context.root;
import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.appsec.api.security.ApiSecuritySampler;
import com.datadog.appsec.event.EventProducerService;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.tabletest.junit.TableTest;

@WithConfig(key = TRACE_OTEL_SEMANTICS_ENABLED, value = "true")
class GatewayBridgeOtelSemanticsForkedTest {
  private CoreTracer tracer;

  @BeforeEach
  void setUp() {
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  @TableTest({
    "Scenario      | Path        | Route       ",
    "Without route | /orders/123 |             ",
    "With route    | /orders/123 | /orders/{id}"
  })
  void apiSecurityReceivesEndpointDataFromOtelServerSpans(String path, String route) {
    TestServerDecorator decorator = new TestServerDecorator();
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    decorator.afterStart(span);
    decorator.onRequest(span, null, URI.create("https://example.com" + path), root());
    if (route != null) {
      HTTP_RESOURCE_DECORATOR.withRoute(span, "GET", route);
    }
    assertEquals(route, span.getTag(Tags.HTTP_ROUTE));

    InstrumentationGateway gateway = new InstrumentationGateway();
    EventProducerService producer = mock(EventProducerService.class);
    when(producer.allSubscribedDataAddresses()).thenReturn(emptyList());
    ApiSecuritySampler sampler = mock(ApiSecuritySampler.class);
    GatewayBridge bridge =
        new GatewayBridge(
            gateway.getSubscriptionService(RequestContextSlot.APPSEC),
            producer,
            () -> sampler,
            emptyList());
    bridge.init();
    AppSecRequestContext appSecContext = new AppSecRequestContext();
    RequestContext requestContext = mock(RequestContext.class);
    when(requestContext.getData(RequestContextSlot.APPSEC)).thenReturn(appSecContext);

    try {
      gateway
          .getCallbackProvider(RequestContextSlot.APPSEC)
          .getCallback(EVENTS.requestEnded())
          .apply(requestContext, span);

      verify(sampler).preSampleRequest(appSecContext, "test-http-server");
      assertEquals(route, appSecContext.getRoute());
      if (route == null) {
        assertNotNull(appSecContext.getHttpUrl());
        assertEquals(path, URI.create(appSecContext.getHttpUrl()).getPath());
      }
    } finally {
      span.finish();
      appSecContext.close();
    }
  }

  private static final class TestServerDecorator
      extends HttpServerDecorator<URI, Void, Integer, Void> {
    @Override
    protected String[] instrumentationNames() {
      return new String[] {"test-http-server"};
    }

    @Override
    protected CharSequence component() {
      return "test-http-server";
    }

    @Override
    public CharSequence spanName() {
      return "http.request";
    }

    @Override
    protected AgentPropagation.ContextVisitor<Void> getter() {
      return null;
    }

    @Override
    protected AgentPropagation.ContextVisitor<Integer> responseGetter() {
      return null;
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
