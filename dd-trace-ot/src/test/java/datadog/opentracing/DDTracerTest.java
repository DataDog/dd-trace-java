package datadog.opentracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.Writer;
import datadog.trace.context.TraceScope;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.Scope;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class DDTracerTest extends DDJavaSpecification {

  @Test
  void testTracerBuilder() throws Exception {
    DDTracer tracer = DDTracer.builder().build();
    assertNotNull(tracer);
    tracer.close();
  }

  @Test
  void testDeprecatedTracerConstructor() throws Exception {
    DDTracer tracer1 = new DDTracer();
    DDTracer tracer2 = new DDTracer("serviceName");
    DDTracer tracer3 = new DDTracer("serviceName", mock(Writer.class), mock(Sampler.class));
    DDTracer tracer4 =
        new DDTracer("serviceName", mock(Writer.class), mock(Sampler.class), new HashMap<>());
    DDTracer tracer5 = new DDTracer(mock(Writer.class));

    assertNotNull(tracer1);
    assertNotNull(tracer2);
    assertNotNull(tracer3);
    assertNotNull(tracer4);
    assertNotNull(tracer5);

    tracer1.close();
    tracer2.close();
    tracer3.close();
    tracer4.close();
    tracer5.close();
  }

  @Test
  void testTracerBuilderWithDefaultWriter() throws Exception {
    DDTracer tracer = DDTracer.builder().writer(DDAgentWriter.builder().build()).build();
    assertNotNull(tracer);
    tracer.close();
  }

  @Test
  void testAccessToTraceSegment() throws Exception {
    DDTracer tracer = DDTracer.builder().writer(DDAgentWriter.builder().build()).build();
    OTSpan span = (OTSpan) tracer.buildSpan("some name").start();
    try (Scope scope = tracer.scopeManager().activate(span)) {
      assertNotNull(tracer);
      assertEquals(span, tracer.activeSpan());
      assertNotNull(tracer.getTraceSegment());
    }

    tracer.close();
  }

  @Test
  void shouldProduceBlackholeScopes() throws Exception {
    ListWriter writer = new ListWriter();
    DDTracer tracer = DDTracer.builder().writer(writer).build();

    OTSpan span = (OTSpan) tracer.buildSpan("some name").start();
    Scope scope = tracer.scopeManager().activate(span);
    TraceScope muteScope = tracer.muteTracing();
    io.opentracing.Span blackholed = tracer.buildSpan("hidden span").start();
    blackholed.finish();
    muteScope.close();
    io.opentracing.Span visibleSpan = tracer.buildSpan("visible span").start();
    visibleSpan.finish();
    scope.close();
    span.finish();

    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    assertEquals(2, writer.firstTrace().size());
    assertEquals(
        Long.toString(writer.firstTrace().get(0).spanContext().getSpanId()),
        span.context().toSpanId());
    assertEquals(
        Long.toString(writer.firstTrace().get(1).spanContext().getSpanId()),
        visibleSpan.context().toSpanId());

    tracer.close();
  }
}
