package datadog.opentracing.resolver;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.opentracing.DDTracer;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import org.junit.jupiter.api.Test;

class DDTracerResolverTest extends DDJavaSpecification {

  private final DDTracerResolver resolver = new DDTracerResolver();

  @Test
  void testResolveTracer() throws Exception {
    io.opentracing.Tracer tracer = TracerResolver.resolveTracer();
    assertInstanceOf(DDTracer.class, tracer);
    tracer.close();
  }

  @Test
  @WithConfig(key = "trace.resolver.enabled", value = "false")
  void testDisableDDTracerResolver() {
    assertNull(resolver.resolve());
  }
}
