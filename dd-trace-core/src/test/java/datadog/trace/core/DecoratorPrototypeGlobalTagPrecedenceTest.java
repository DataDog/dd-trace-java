package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.common.writer.ListWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression test for a precedence bug between global tags ({@code DD_TAGS} / {@code
 * DD_TRACE_SPAN_TAGS}, seeded onto the context during construction) and a decorator's own identity
 * (applied in {@code BaseDecorator.afterStart}, mirrored here via {@code
 * AgentSpan#applyOverwriting}). A global tag on a decorator-owned key (e.g. {@code component}) must
 * never suppress the decorator's own value -- see {@code AgentSpan#apply} (fill-absent,
 * construction seam) vs {@code AgentSpan#applyOverwriting} (unconditional, decorator seam).
 */
public class DecoratorPrototypeGlobalTagPrecedenceTest extends DDCoreJavaSpecification {

  private ListWriter writer;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
  }

  @AfterEach
  void cleanup() {
    // no-op: each test builds and closes its own tracer
  }

  @Test
  void decoratorIdentityOverwritesGlobalTagOnSameKey() {
    // Simulates `dd.trace.span.tags=component:global-value` clashing with a decorator's own
    // component.
    injectSysConfig("dd.trace.span.tags", "component:global-value");
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").withServiceName("foo").start();
      try {
        // Global tag is seeded at construction time, before any decorator runs.
        assertEquals("global-value", span.getTags().get(COMPONENT));

        // Decorator's afterStart mirrors BaseDecorator.doAfterStart: applyOverwriting must win.
        SpanPrototype decoratorPrototype =
            SpanPrototype.builder().initComponentAndIntegration("decorator-component").build();
        span.applyOverwriting(decoratorPrototype);

        assertEquals("decorator-component", span.getTags().get(COMPONENT));
      } finally {
        span.finish();
      }
    } finally {
      tracer.close();
    }
  }

  @Test
  void fillAbsentApplyWouldLetGlobalTagWinDemonstratingWhyOverwritingIsNeeded() {
    // Documents the bug applyOverwriting fixes: the fill-absent `apply` seam is correct for
    // construction-time seeding, but would leave the global tag in place if used for decorators.
    injectSysConfig("dd.trace.span.tags", "component:global-value");
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").withServiceName("foo").start();
      try {
        assertEquals("global-value", span.getTags().get(COMPONENT));

        SpanPrototype decoratorPrototype =
            SpanPrototype.builder().initComponentAndIntegration("decorator-component").build();
        span.apply(decoratorPrototype);

        // Fill-absent semantics: the key is already present (global tag), so it is left alone.
        assertEquals("global-value", span.getTags().get(COMPONENT));
      } finally {
        span.finish();
      }
    } finally {
      tracer.close();
    }
  }
}
