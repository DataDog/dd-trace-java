package datadog.opentracing;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpanContext;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class TypeConverterTest extends DDJavaSpecification {

  TypeConverter typeConverter = new TypeConverter(new DefaultLogHandler());

  @Test
  void shouldAvoidTheNoopSpanWrapperAllocation() {
    AgentSpan noopAgentSpan = noopSpan();
    assertSame(typeConverter.toSpan(noopAgentSpan), typeConverter.toSpan(noopAgentSpan));
  }

  @Test
  void shouldAvoidExtraAllocationForASpanWrapper() throws Exception {
    CoreTracer testTracer = CoreTracer.builder().writer(new ListWriter()).build();
    try {
      AgentSpan span1 = testTracer.buildSpan("datadog", "test").start();
      AgentSpan span2 = testTracer.buildSpan("datadog", "test").start();

      // return the same wrapper for the same span
      assertSame(typeConverter.toSpan(span1), typeConverter.toSpan(span1));
      // return a distinct wrapper for another span
      assertNotSame(typeConverter.toSpan(span1), typeConverter.toSpan(span2));
    } finally {
      testTracer.close();
    }
  }

  @Test
  void shouldAvoidTheNoopContextWrapperAllocation() {
    datadog.trace.bootstrap.instrumentation.api.AgentSpanContext noopContext = noopSpanContext();
    assertSame(typeConverter.toSpanContext(noopContext), typeConverter.toSpanContext(noopContext));
  }

  @Test
  void shouldAvoidTheNoopScopeWrapperAllocation() {
    datadog.trace.bootstrap.instrumentation.api.AgentScope noopScopeInstance = noopScope();
    assertSame(
        typeConverter.toScope(noopScopeInstance, true),
        typeConverter.toScope(noopScopeInstance, true));
    assertSame(
        typeConverter.toScope(noopScopeInstance, false),
        typeConverter.toScope(noopScopeInstance, false));
    // noop scopes expected to be the same despite the finishSpanOnClose flag
    assertSame(
        typeConverter.toScope(noopScopeInstance, true),
        typeConverter.toScope(noopScopeInstance, false));
    assertSame(
        typeConverter.toScope(noopScopeInstance, false),
        typeConverter.toScope(noopScopeInstance, true));
  }
}
