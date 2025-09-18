package datadog.trace.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.core.CoreTracer.CoreSpanBuilder;
import datadog.trace.core.CoreTracer.ReusableSingleSpanBuilder;
import datadog.trace.core.CoreTracer.ReusableSingleSpanBuilderThreadLocalCache;
import org.junit.Test;

public final class CoreTracerTest {
  static final CoreTracer TRACER = CoreTracer.builder().build();
  static final ReusableSingleSpanBuilderThreadLocalCache CACHE =
      new ReusableSingleSpanBuilderThreadLocalCache(TRACER);

  @Test
  public void buildSpan() {
    // buildSpan allows for constructing multiple spans from each returned CoreSpanBuilder
    // so buildSpan cannot recycle objects - even when SpanBuilder reuse is enabled
    CoreSpanBuilder builder1 = TRACER.buildSpan("foo", "bar");

    // need to build/start a span to prove that builder isn't being recycled
    builder1.start();

    CoreSpanBuilder builder2 = TRACER.buildSpan("foo", "bar");
    builder2.start();

    assertNotSame(builder1, builder2);
  }

  @Test
  public void singleUseSpanBuilder() {
    CoreSpanBuilder builder1 = TRACER.singleSpanBuilder("foo", "bar");
    builder1.start();

    CoreSpanBuilder builder2 = TRACER.singleSpanBuilder("baz", "quux");
    builder2.start();

    if (Config.get().isSpanBuilderReuseEnabled()) {
      assertSame(builder1, builder2);
    } else {
      assertNotSame(builder1, builder2);
    }
  }

  @Test
  public void spanBuilderReuse() {
    // Doesn't call reuseSpanBuilder(String, CharSeq) directly, since that will fail when the Config
    // is disabled
    ReusableSingleSpanBuilder builder1 =
        CoreTracer.reuseSingleSpanBuilder(TRACER, CACHE, "foo", "bar");
    assertTrue(builder1.inUse);

    builder1.start();
    assertFalse(builder1.inUse);

    ReusableSingleSpanBuilder builder2 =
        CoreTracer.reuseSingleSpanBuilder(TRACER, CACHE, "baz", "quux");
    assertTrue(builder2.inUse);
    assertSame(builder1, builder2);

    builder2.start();
    assertFalse(builder2.inUse);
  }

  @Test
  public void spanBuilderReuse_stillInUse() {
    // Doesn't call reuseSpanBuilder(String, CharSeq) directly, since that will fail when the Config
    // is disabled
    ReusableSingleSpanBuilder builder1 =
        CoreTracer.reuseSingleSpanBuilder(TRACER, CACHE, "foo", "bar");
    assertTrue(builder1.inUse);

    ReusableSingleSpanBuilder builder2 =
        CoreTracer.reuseSingleSpanBuilder(TRACER, CACHE, "baz", "quux");
    assertTrue(builder2.inUse);
    assertNotSame(builder1, builder2);

    builder2.start();
    assertFalse(builder2.inUse);

    builder1.start();
    assertFalse(builder1.inUse);
  }
}
