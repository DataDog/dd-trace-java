package datadog.trace.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.core.CoreTracer.CoreSpanBuilder;
import datadog.trace.core.CoreTracer.CoreSpanBuilderThreadLocalCache;
import org.junit.Test;

public final class CoreTracerTest {
  static final CoreTracer TRACER = CoreTracer.builder().build();
  static final CoreSpanBuilderThreadLocalCache CACHE = new CoreSpanBuilderThreadLocalCache(TRACER);

  @Test
  public void buildSpan() {
    CoreSpanBuilder builder1 = TRACER.buildSpan("foo", "bar");
    CoreSpanBuilder builder2 = TRACER.buildSpan("foo", "bar");

    assertNotSame(builder1, builder2);
  }

  @Test
  public void singleUseSpanBuilder() {
    CoreSpanBuilder builder1 = TRACER.buildSpan("foo", "bar");
    builder1.start();

    CoreSpanBuilder builder2 = TRACER.buildSpan("baz", "quux");
    builder2.start();

    if (Config.get().isSpanBuilderReuseEnabled()) {
      assertSame(builder1, builder2);
    } else {
      assertNotSame(builder1, builder2);
    }
  }

  @Test
  public void spanBuilderReuse() {
    CoreSpanBuilder builder1 = CoreTracer.reuseSpanBuilder(TRACER, CACHE, "foo", "bar");
    assertTrue(builder1.inUse);

    builder1.start();
    assertFalse(builder1.inUse);

    CoreSpanBuilder builder2 = CoreTracer.reuseSpanBuilder(TRACER, CACHE, "baz", "quux");
    assertTrue(builder2.inUse);
    assertSame(builder1, builder2);

    builder2.start();
    assertFalse(builder2.inUse);
  }

  @Test
  public void spanBuilderReuse_stillInUse() {
    CoreSpanBuilder builder1 = CoreTracer.reuseSpanBuilder(TRACER, CACHE, "foo", "bar");
    assertTrue(builder1.inUse);

    CoreSpanBuilder builder2 = CoreTracer.reuseSpanBuilder(TRACER, CACHE, "baz", "quux");
    assertTrue(builder2.inUse);
    assertNotSame(builder1, builder2);

    builder2.start();
    assertFalse(builder2.inUse);

    builder1.start();
    assertFalse(builder1.inUse);
  }
}
