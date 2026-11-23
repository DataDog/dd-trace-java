package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_CLIENT_CONFIG_PATTERN_MATCH;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.MANUAL_INSTRUMENTATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.decorator.HttpDecoratorsOtelSemanticsForkedTest.ClientRequest;
import datadog.trace.bootstrap.instrumentation.decorator.HttpDecoratorsOtelSemanticsForkedTest.TestClientDecorator;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@WithConfig(key = TRACE_OTEL_SEMANTICS_ENABLED, value = "true")
@WithConfig(
    key = TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING,
    value = "/orders/*:/orders/{id},/raw/*:*")
class HttpClientPathMappingOtelSemanticsForkedTest {
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
    "Scenario                  | Method | Path        | Expected resource | Expected method",
    "Unmapped path             | GET    | /health/123 | GET               | GET            ",
    "Mapped path               | GET    | /orders/123 | GET /orders/{id}  | GET            ",
    "Mapped extension method   | BREW   | /orders/123 | HTTP /orders/{id} | _OTHER         ",
    "Unmapped extension method | BREW   | /health/123 | HTTP              | _OTHER         ",
    "Mapped lowercase method   | get    | /orders/123 | HTTP /orders/{id} | _OTHER         ",
    "Mapped missing method     |        | /orders/123 | HTTP /orders/{id} |                ",
    "Keep original path        | GET    | /raw/123    | GET /raw/123      | GET            "
  })
  void explicitPathMappingsOverrideOtelClientNames(
      String method, String path, String expectedResource, String expectedMethod) {
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    TestClientDecorator decorator = new TestClientDecorator();

    decorator.afterStart(span);
    decorator.onRequest(span, new ClientRequest(method, URI.create("https://example.com" + path)));
    span.finish();

    assertEquals(expectedResource, span.getResourceName().toString());
    assertEquals(expectedMethod, span.getTag(KnownTags.HTTP_METHOD_OTEL_NAME));
  }

  @Test
  @WithConfig(key = "trace.URLAsResourceNameRule.enabled", value = "false")
  void explicitPathMappingsApplyWithoutUrlResourceNameRule() {
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    TestClientDecorator decorator = new TestClientDecorator();

    decorator.afterStart(span);
    decorator.onRequest(
        span, new ClientRequest("GET", URI.create("https://example.com/orders/123")));
    span.finish();

    assertEquals("GET /orders/{id}", span.getResourceName().toString());
    assertEquals(HTTP_CLIENT_CONFIG_PATTERN_MATCH, span.getResourceNamePriority());
  }

  @Test
  void explicitPathMappingsPreserveManualResourceNames() {
    DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
    span.setResourceName("custom operation", MANUAL_INSTRUMENTATION);
    TestClientDecorator decorator = new TestClientDecorator();

    decorator.afterStart(span);
    decorator.onRequest(
        span, new ClientRequest("GET", URI.create("https://example.com/orders/123")));
    span.finish();

    assertEquals("custom operation", span.getResourceName().toString());
    assertEquals(MANUAL_INSTRUMENTATION, span.getResourceNamePriority());
  }
}
