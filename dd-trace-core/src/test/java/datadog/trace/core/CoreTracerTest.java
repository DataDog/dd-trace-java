package datadog.trace.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import datadog.trace.core.CoreTracer.CoreSpanBuilder;
import datadog.trace.core.CoreTracer.CoreSpanBuilderThreadLocalCache;
import org.junit.Test;

public class CoreTracerTest {
  static final CoreTracer createTracer() {
    return CoreTracer.builder().build();
  }

  @Test
  public void spanBuilderReuse() {
    CoreTracer tracer = createTracer();
    CoreSpanBuilderThreadLocalCache cache = new CoreSpanBuilderThreadLocalCache(tracer);

    CoreSpanBuilder builder1 = CoreTracer.reuseSpanBuilder(tracer, cache, "foo", "bar");
    assertTrue(builder1.inUse);

    builder1.start();
    assertFalse(builder1.inUse);

    CoreSpanBuilder builder2 = CoreTracer.reuseSpanBuilder(tracer, cache, "baz", "quux");
    assertTrue(builder2.inUse);
    assertSame(builder1, builder2);

    builder2.start();
    assertFalse(builder2.inUse);
  }

  @Test
  public void spanBuilderReuse_stillInUse() {
    CoreTracer tracer = createTracer();
    CoreSpanBuilderThreadLocalCache cache = new CoreSpanBuilderThreadLocalCache(tracer);

    CoreSpanBuilder builder1 = CoreTracer.reuseSpanBuilder(tracer, cache, "foo", "bar");
    assertTrue(builder1.inUse);

    CoreSpanBuilder builder2 = CoreTracer.reuseSpanBuilder(tracer, cache, "baz", "quux");
    assertTrue(builder2.inUse);
    assertNotSame(builder1, builder2);

    builder2.start();
    assertFalse(builder2.inUse);

    builder1.start();
    assertFalse(builder1.inUse);
  }
}
