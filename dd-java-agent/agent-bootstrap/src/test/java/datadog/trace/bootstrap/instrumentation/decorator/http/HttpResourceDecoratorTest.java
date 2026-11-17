package datadog.trace.bootstrap.instrumentation.decorator.http;

import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.context.ContextScope;
import datadog.trace.api.normalize.HttpResourceNames;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = HTTP_SERVER_ROUTE_BASED_NAMING, value = "false")
class HttpResourceDecoratorTest {

  private static CoreTracer tracer;

  @BeforeAll
  static void setupSpec() {
    tracer = CoreTracer.builder().build();
  }

  @AfterAll
  static void cleanupSpec() {
    tracer.close();
  }

  @BeforeEach
  void setup() throws ReflectiveOperationException {
    Method reset = HttpResourceNames.class.getDeclaredMethod("reset");
    reset.setAccessible(true);
    reset.invoke(null);
  }

  @Test
  void testThatResourceNameIsNotChanged() {
    // given
    AgentSpan span = tracer.startSpan("test", "test");

    // when
    ContextScope scope = AgentTracer.activateSpan(span);
    decorator().withRoute(span, "GET", "/not-the-resource-name");
    scope.close();
    span.finish();

    // then
    assertEquals("test", span.getResourceName().toString());
  }

  @Test
  void stillUsesTheSimpleNormalizerByDefault() {
    // given
    AgentSpan span = tracer.startSpan("test", "test");

    // when
    decorator().withServerPath(span, "GET", "/asdf/1234", false);

    // then
    assertEquals("GET /asdf/?", span.getResourceName().toString());
  }

  @Test
  @WithConfig(key = TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, value = "/asdf/*:/test")
  void usesTheAntMatchingNormalizerWhenConfiguredTo() {
    AgentSpan span = tracer.startSpan("test", "test");

    // when
    decorator().withServerPath(span, "GET", "/asdf/1234", false);

    // then
    assertEquals("GET /test", span.getResourceName().toString());
  }

  @Test
  @WithConfig(key = TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, value = "/asdf/*:/test")
  void fallsBackToSimpleNormalizer() {
    AgentSpan span = tracer.startSpan("test", "test");

    // when
    decorator().withServerPath(span, "GET", "/unknown/1234", false);

    // then
    assertEquals("GET /unknown/?", span.getResourceName().toString());
  }

  @Test
  @WithConfig(key = TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, value = "/a/*:/test")
  @WithConfig(key = "trace.URLAsResourceNameRule.enabled", value = "false")
  void returnsSaneDefaultWhenDisabled() {
    AgentSpan span = tracer.startSpan("test", "test");

    // when
    decorator().withServerPath(span, "GET", "/unknown/1234", false);

    // then
    assertEquals("/", span.getResourceName().toString());
  }

  @Test
  @WithConfig(key = TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, value = "/a/*:/test")
  void returnsMappedClientPath() {
    AgentSpan span = tracer.startSpan("test", "test");

    // when
    decorator().withClientPath(span, "GET", "/a/foo");

    // then
    assertEquals("GET /test", span.getResourceName().toString());
  }

  @Test
  @WithConfig(key = TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, value = "/a/*:*")
  void returnsOriginalClientPath() {
    AgentSpan span = tracer.startSpan("test", "test");

    // when
    decorator().withClientPath(span, "GET", "/a/foo");

    // then
    assertEquals("GET /a/foo", span.getResourceName().toString());
  }

  // Need a new one for every test after config injection
  private static HttpResourceDecorator decorator() {
    return new HttpResourceDecorator();
  }
}
