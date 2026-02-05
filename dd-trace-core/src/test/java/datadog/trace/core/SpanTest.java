package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

public class SpanTest {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  @Test
  @DisplayName("setTag: TagMap.Entry")
  public void setTagEntry() {
    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setTag(TagMap.Entry.create("message", "hello"));

    assertEquals("hello", span.getTag("message"));
  }

  @Test
  @DisplayName("setMeric: TagMap.Entry")
  public void setMetricEntry() {
    AgentSpan span = TRACER.startSpan("foo", "foo");
    span.setMetric(TagMap.Entry.create("metric", 20L));

    assertEquals(Long.valueOf(20L), span.getTag("metric"));
  }
}
