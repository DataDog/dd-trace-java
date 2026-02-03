package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.Test;

public class SpanTest {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  @Test
  public void setMetric_int() {
    int expected = 2;

    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric("int", expected);

    assertEquals(Integer.valueOf(expected), span.getTag("int"));
  }

  @Test
  public void setMetric_long() {
    long expected = 20L;

    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric("long", expected);

    assertEquals(Long.valueOf(expected), span.getTag("long"));
  }

  @Test
  public void setMetric_float() {
    float expected = 2.718F;

    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric("float", expected);

    assertEquals(Float.valueOf(expected), span.getTag("float"));
  }

  @Test
  public void setMetric_double() {
    double expected = 3.1415D;

    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric("double", expected);

    assertEquals(Double.valueOf(expected), span.getTag("double"));
  }
}
