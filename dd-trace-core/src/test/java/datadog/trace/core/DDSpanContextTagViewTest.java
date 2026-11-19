package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The span's cached view of its tag stores keeps the stores it was created over, so a custom store
 * the span creates afterwards must replace the view rather than be missed by it.
 */
class DDSpanContextTagViewTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void aCustomTagWrittenAfterTheViewIsCreatedIsSeenThroughANewView() {
    tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "op").start();
    try {
      DDSpanContext context = (DDSpanContext) span.spanContext();
      assertEquals(null, context.getTags().get("custom.first")); // creates the view

      span.setTag("custom.first", "a"); // the span's first custom store: the view is dropped
      span.setTag("custom.second", "b");

      TagMap tags = context.getTags();
      assertEquals("a", tags.get("custom.first"));
      assertEquals("b", tags.get("custom.second"));
    } finally {
      span.finish();
    }
  }

  @Test
  void aCustomTagCopiedInFromAMapIsStoredWithoutAView() {
    tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "op").start();
    try {
      DDSpanContext context = (DDSpanContext) span.spanContext();
      context.setAllTags(TagMap.fromMap(Collections.singletonMap("custom.copied", "c")));
      span.setTag("custom.later", "d");

      TagMap tags = context.getTags();
      assertEquals("c", tags.get("custom.copied"));
      assertEquals("d", tags.get("custom.later"));
    } finally {
      span.finish();
    }
  }
}
