package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.CoreTracer.CoreSpanBuilder;
import datadog.trace.core.CoreTracer.ReusableSingleSpanBuilder;
import datadog.trace.core.CoreTracer.ReusableSingleSpanBuilderThreadLocalCache;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

// named CoreTracerTest2 to avoid collision with Groovy which appears to have messed up test
// coverage
public final class CoreTracerTest2 {
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

  @Test
  public void spanBuilderReuse_abandoned() {
    // Doesn't call reuseSpanBuilder(String, CharSeq) directly, since that will fail when the Config
    // is disabled

    ReusableSingleSpanBuilder abandonedBuilder =
        CoreTracer.reuseSingleSpanBuilder(TRACER, CACHE, "foo", "bar");
    assertTrue(abandonedBuilder.inUse);

    // Requesting the next builder will replace the previous one in the thread local cache
    // This is done so that an abandoned builder doesn't permanently burn the cache for a thread
    ReusableSingleSpanBuilder builder1 =
        CoreTracer.reuseSingleSpanBuilder(TRACER, CACHE, "baz", "quux");
    assertTrue(builder1.inUse);
    assertNotSame(abandonedBuilder, builder1);

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
  public void init_twice() {
    ReusableSingleSpanBuilder builder = new ReusableSingleSpanBuilder(TRACER);
    builder.init("foo", "bar");
    assertTrue(builder.inUse);
    assertEquals("foo", builder.instrumentationName);
    assertEquals("bar", builder.operationName);

    assertThrows(AssertionError.class, () -> builder.init("baz", "quux"));
  }

  @Test
  public void reset_twice() {
    ReusableSingleSpanBuilder builder = new ReusableSingleSpanBuilder(TRACER);
    builder.reset("foo", "bar");
    assertTrue(builder.inUse);
    assertEquals("foo", builder.instrumentationName);
    assertEquals("bar", builder.operationName);

    assertFalse(builder.reset("baz", "quux"));
    assertEquals("foo", builder.instrumentationName);
    assertEquals("bar", builder.operationName);
  }

  @Test
  public void reset_and_start() {
    ReusableSingleSpanBuilder builder = new ReusableSingleSpanBuilder(TRACER);
    builder.reset("foo", "bar");
    assertTrue(builder.inUse);
    assertEquals("foo", builder.instrumentationName);
    assertEquals("bar", builder.operationName);

    AgentSpan span = builder.start();
    assertEquals(span.getOperationName(), "bar");
  }

  @Test
  public void init_and_start() {
    ReusableSingleSpanBuilder builder = new ReusableSingleSpanBuilder(TRACER);
    builder.reset("foo", "bar");
    assertTrue(builder.inUse);
    assertEquals("foo", builder.instrumentationName);
    assertEquals("bar", builder.operationName);

    AgentSpan span = builder.start();
    assertFalse(builder.inUse);
    assertEquals(span.getOperationName(), "bar");

    builder.reset("baz", "quux");
    assertTrue(builder.inUse);
    assertEquals("baz", builder.instrumentationName);
    assertEquals("quux", builder.operationName);
  }

  @Test
  public void start_not_inUse() {
    ReusableSingleSpanBuilder builder = new ReusableSingleSpanBuilder(TRACER);
    assertThrows(AssertionError.class, () -> builder.start());
  }

  @Test
  public void noInterceptorsTest() {
    // No interceptors should return the original list to avoid allocation
    DDSpan span = (DDSpan) TRACER.startSpan("foo", "foo");

    SpanList list = SpanList.of(span);
    List<DDSpan> interceptedList =
        CoreTracer.interceptCompleteTrace(Collections.emptySortedSet(), list);
    assertSame(list, interceptedList);
  }

  @Test
  public void interceptNoInterceptors() {
    // No interceptors should return the original list to avoid allocation
    DDSpan span = (DDSpan) TRACER.startSpan("foo", "foo");

    SpanList list = SpanList.of(span);
    List<DDSpan> interceptedList =
        CoreTracer.interceptCompleteTrace(Collections.emptySortedSet(), list);
    assertSame(list, interceptedList);
  }

  @Test
  public void interceptEmptyList() {
    DDSpan span = (DDSpan) TRACER.startSpan("foo", "foo");
    SortedSet<TraceInterceptor> interceptors = interceptors((list) -> SpanList.of(span));

    SpanList list = new SpanList(0); // not using EMPTY deliberately
    List<DDSpan> interceptedList = CoreTracer.interceptCompleteTrace(interceptors, list);
    assertTrue(interceptedList.isEmpty());
  }

  @Test
  public void interceptUnchanged() {
    SortedSet<TraceInterceptor> interceptors = interceptors((list) -> list, (list) -> list);

    DDSpan span = (DDSpan) TRACER.startSpan("foo", "foo");
    SpanList list = SpanList.of(span);
    List<DDSpan> interceptedList = CoreTracer.interceptCompleteTrace(interceptors, list);
    assertSame(list, interceptedList);
  }

  @Test
  public void interceptNewList() {
    DDSpan substituteSpan = (DDSpan) TRACER.startSpan("sub", "sub");
    SpanList substituteList = SpanList.of(substituteSpan);
    SortedSet<TraceInterceptor> interceptors =
        interceptors((list) -> list, (list) -> substituteList);

    DDSpan span = (DDSpan) TRACER.startSpan("foo", "foo");
    SpanList list = SpanList.of(span);
    List<DDSpan> interceptedList = CoreTracer.interceptCompleteTrace(interceptors, list);
    assertEquals(1, interceptedList.size());
    assertEquals("sub", interceptedList.get(0).getOperationName());
  }

  @Test
  public void interceptAlteredList() {
    // This is an unlikely case and arguably not something we need to support

    DDSpan substituteSpan = (DDSpan) TRACER.startSpan("sub", "sub");
    SortedSet<TraceInterceptor> interceptors =
        interceptors(
            (list) -> list,
            (list) -> {
              List erasedList = (List) list;
              erasedList.clear();
              erasedList.add(substituteSpan);
              return erasedList;
            });

    DDSpan span = (DDSpan) TRACER.startSpan("foo", "foo");
    SpanList list = SpanList.of(span);
    List<DDSpan> interceptedList = CoreTracer.interceptCompleteTrace(interceptors, list);
    assertNotSame(interceptedList, list);
    assertEquals(1, interceptedList.size());
    assertEquals("sub", interceptedList.get(0).getOperationName());
  }

  static final SortedSet<TraceInterceptor> interceptors(TestInterceptor... interceptors) {
    TreeSet<TraceInterceptor> interceptorSet =
        new TreeSet<>((lhs, rhs) -> Integer.compare(lhs.priority(), rhs.priority()));
    for (int i = 0; i < interceptors.length; ++i) {
      int priority = i;
      TestInterceptor interceptor = interceptors[i];

      interceptorSet.add(
          new TraceInterceptor() {
            @Override
            public int priority() {
              return priority;
            }

            @Override
            public Collection<? extends MutableSpan> onTraceComplete(
                Collection<? extends MutableSpan> trace) {
              return interceptor.onTraceComplete(trace);
            }
          });
    }
    return interceptorSet;
  }

  // Matches TraceInterceptor but priority is implied in interceptors
  // Only having onTraceComplete allows this to @FunctionalInterface and a little nicer for a test
  @FunctionalInterface
  interface TestInterceptor {
    Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace);
  }
}
