package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

public class SpanTest {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  @Test
  @DisplayName("setMetric: int")
  public void setMetricInt() {
    int expected = 2;

    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric("int", expected);

    Object value = span.getTag("int");
    assertInstanceOf(Integer.class, value);
    assertEquals(Integer.valueOf(expected), value);
  }

  @Test
  @DisplayName("setMetric: long")
  public void setMetricLong() {
    long expected = 20L;

    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric("long", expected);

    Object value = span.getTag("long");
    assertInstanceOf(Long.class, value);
    assertEquals(Long.valueOf(expected), value);
  }

  @Test
  @DisplayName("setMetric: float")
  public void setMetricFloat() {
    float expected = 2.718F;

    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric("float", expected);

    Object value = span.getTag("float");
    assertInstanceOf(Float.class, value);
    assertEquals(Float.valueOf(expected), value);
  }

  @Test
  @DisplayName("setMetric: double")
  public void setMetricDouble() {
    double expected = 3.1415D;

    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric("double", expected);

    Object value = span.getTag("double");
    assertInstanceOf(Double.class, value);
    assertEquals(Double.valueOf(expected), value);
  }
  
  @Test
  @DisplayName("setTag: TagMap.Entry")
  public void setTagEntry() {
    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setTag(TagMap.Entry.create("message", "hello"));

    assertEquals("hello", span.getTag("message"));
  }

  @Test
  @DisplayName("setTag: null")
  public void setTagEntryNull() {
    AgentSpan span = TRACER.startSpan("foo", "foo");
    int initialSize = span.getTags().size();

    span.setTag(null);

    assertEquals(initialSize, span.getTags().size());
  }

  @Test
  @DisplayName("setMetric: TagMap.Entry")
  public void setMetricEntry() {
    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric(TagMap.Entry.create("metric", 20L));

    assertEquals(Long.valueOf(20L), span.getTag("metric"));
  }

  @Test
  @DisplayName("setMetric: null")
  public void setMetricEntryNull() {
    AgentSpan span = TRACER.startSpan("foo", "foo");
    int initialSize = span.getTags().size();

    span.setMetric(null);

    assertEquals(initialSize, span.getTags().size());
  }
}
