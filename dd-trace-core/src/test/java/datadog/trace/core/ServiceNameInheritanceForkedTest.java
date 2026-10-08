package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.ServiceNameSources;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// SpanNaming caches the removal setting, so these tests need their own JVM.
@WithConfig(key = TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v0")
@WithConfig(key = TracerConfig.TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED, value = "true")
@WithConfig(key = TracerConfig.TRACE_INFERRED_PROXY_SERVICES_ENABLED, value = "true")
class ServiceNameInheritanceForkedTest extends DDCoreJavaSpecification {

  @ParameterizedTest
  @CsvSource({"checkout, servlet-context", "special-checkout, manual"})
  void descendantsRetainApplicationServiceUnderInferredProxy(String service, String source) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).serviceName("checkout").build();
    assertFalse(SpanNaming.instance().namingSchema().allowInferredServices());

    DDSpan proxy = (DDSpan) tracer.buildSpan("test", "aws.apigateway").start();
    proxy.setServiceName("gateway.example.com", "inferred-proxy");
    proxy.setTag("_dd.inferred_span", 1);

    DDSpan entry =
        (DDSpan) tracer.buildSpan("test", "servlet.request").asChildOf(proxy.spanContext()).start();
    // HTTP server decoration resets the application entry service after creating it.
    entry.setServiceName(service, source);
    DDSpan child =
        (DDSpan) tracer.buildSpan("test", "application").asChildOf(entry.spanContext()).start();
    DDSpan grandchild =
        (DDSpan)
            tracer
                .buildSpan("test", "database.query")
                .asChildOf(child.spanContext())
                .withServiceName(SpanNaming.instance().namingSchema().database().service("mysql"))
                .start();

    grandchild.finish();
    child.finish();
    entry.finish();
    proxy.finish();

    assertEquals("gateway.example.com", proxy.getServiceName());
    for (DDSpan span : new DDSpan[] {entry, child, grandchild}) {
      assertEquals(service, span.getServiceName());
      assertEquals(source, span.getServiceNameSource());
    }
  }

  @ParameterizedTest
  @CsvSource({"checkout, servlet-context", "special-checkout, manual"})
  void ordinaryRootStillOverridesIntermediateAndExplicitServices(String service, String source) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).serviceName("checkout").build();
    DDSpan root = (DDSpan) tracer.buildSpan("test", "servlet.request").start();
    root.setServiceName(service, source);
    DDSpan child =
        (DDSpan) tracer.buildSpan("test", "application").asChildOf(root.spanContext()).start();
    child.setServiceName("intermediate-service", ServiceNameSources.MANUAL);
    DDSpan grandchild =
        (DDSpan)
            tracer
                .buildSpan("test", "database.query")
                .asChildOf(child.spanContext())
                .withServiceName("explicit-service")
                .start();

    grandchild.finish();
    child.finish();
    root.finish();

    assertEquals(service, grandchild.getServiceName());
    assertEquals(source, grandchild.getServiceNameSource());
  }
}
