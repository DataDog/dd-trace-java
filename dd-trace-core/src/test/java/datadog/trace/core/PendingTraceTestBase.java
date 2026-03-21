package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

public abstract class PendingTraceTestBase extends DDCoreSpecification {

  ListWriter writer;
  CoreTracer tracer;
  DDSpan rootSpan;
  PendingTrace traceCollector;

  @BeforeEach
  void setup() throws Exception {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
    rootSpan = (DDSpan) tracer.buildSpan("fakeOperation").start();
    traceCollector = (PendingTrace) rootSpan.context().getTraceCollector();
    assertEquals(0, traceCollector.size());
    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertFalse(traceCollector.isRootSpanWritten());
  }

  @AfterEach
  void cleanup() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void singleSpanGetsAddedToTraceAndWrittenWhenFinished() throws Exception {
    rootSpan.finish();
    writer.waitForTraces(1);

    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(1, writer.size());
    assertEquals(1, writer.get(0).size());
    assertEquals(rootSpan, writer.get(0).get(0));
    assertEquals(1, writer.traceCount.get());
  }

  @Test
  void childFinishesBeforeParent() throws Exception {
    DDSpan child = (DDSpan) tracer.buildSpan("child").asChildOf(rootSpan.context()).start();

    assertEquals(2, traceCollector.getPendingReferenceCount());

    child.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(1, traceCollector.getSpans().size());
    // spans deque: addFirst means newest is at front
    assertEquals(
        child,
        ((java.util.concurrent.ConcurrentLinkedDeque<DDSpan>) traceCollector.getSpans()).peek());
    assertEquals(0, writer.size());

    rootSpan.finish();
    writer.waitForTraces(1);

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(1, writer.size());
    assertEquals(2, writer.get(0).size());
    assertTrue(writer.get(0).contains(rootSpan));
    assertTrue(writer.get(0).contains(child));
    assertEquals(1, writer.traceCount.get());
  }

  @Test
  void parentFinishesBeforeChildWhichHoldsUpTrace() throws Exception {
    DDSpan child = (DDSpan) tracer.buildSpan("child").asChildOf(rootSpan.context()).start();

    assertEquals(2, traceCollector.getPendingReferenceCount());

    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(1, traceCollector.getSpans().size());
    assertEquals(
        rootSpan,
        ((java.util.concurrent.ConcurrentLinkedDeque<DDSpan>) traceCollector.getSpans()).peek());
    assertEquals(0, writer.size());

    child.finish();
    writer.waitForTraces(1);

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(1, writer.size());
    assertEquals(2, writer.get(0).size());
    assertTrue(writer.get(0).contains(child));
    assertTrue(writer.get(0).contains(rootSpan));
    assertEquals(1, writer.traceCount.get());
  }

  @Test
  void childSpansCreatedAfterTraceWrittenReportedSeparately() throws Exception {
    rootSpan.finish();
    // this shouldn't happen, but it's possible users of the api
    // may incorrectly add spans after the trace is reported.
    // in those cases we should still decrement the pending trace count
    DDSpan childSpan = (DDSpan) tracer.buildSpan("child").asChildOf(rootSpan.context()).start();
    childSpan.finish();
    writer.waitForTraces(2);

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(2, writer.size());
    assertEquals(1, writer.get(0).size());
    assertEquals(rootSpan, writer.get(0).get(0));
    assertEquals(1, writer.get(1).size());
    assertEquals(childSpan, writer.get(1).get(0));
  }

  @Test
  void testGetCurrentTimeNano() {
    // Generous 5 seconds to execute this test
    assertTrue(
        Math.abs(
                TimeUnit.NANOSECONDS.toSeconds(traceCollector.getCurrentTimeNano())
                    - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            < 5);
  }

  @Test
  void partialFlush() throws Exception {
    injectSysConfig(PARTIAL_FLUSH_MIN_SPANS, "2");
    CoreTracer quickTracer = tracerBuilder().writer(writer).build();
    try {
      DDSpan rootSpanLocal = (DDSpan) quickTracer.buildSpan("root").start();
      PendingTrace traceCollectorLocal = (PendingTrace) rootSpanLocal.context().getTraceCollector();
      DDSpan child1 =
          (DDSpan) quickTracer.buildSpan("child1").asChildOf(rootSpanLocal.context()).start();
      DDSpan child2 =
          (DDSpan) quickTracer.buildSpan("child2").asChildOf(rootSpanLocal.context()).start();

      assertEquals(3, traceCollectorLocal.getPendingReferenceCount());

      child2.finish();

      assertEquals(2, traceCollectorLocal.getPendingReferenceCount());
      assertEquals(1, traceCollectorLocal.getSpans().size());
      assertEquals(
          child2,
          ((java.util.concurrent.ConcurrentLinkedDeque<DDSpan>) traceCollectorLocal.getSpans())
              .peek());
      assertEquals(0, writer.size());
      assertEquals(0, writer.traceCount.get());

      child1.finish();
      writer.waitForTraces(1);

      assertEquals(1, traceCollectorLocal.getPendingReferenceCount());
      assertTrue(traceCollectorLocal.getSpans().isEmpty());
      assertEquals(1, writer.size());
      assertEquals(2, writer.get(0).size());
      assertTrue(writer.get(0).contains(child1));
      assertTrue(writer.get(0).contains(child2));
      assertEquals(1, writer.traceCount.get());

      rootSpanLocal.finish();
      writer.waitForTraces(2);

      assertEquals(0, traceCollectorLocal.getPendingReferenceCount());
      assertTrue(traceCollectorLocal.getSpans().isEmpty());
      assertEquals(2, writer.size());
      assertEquals(1, writer.get(1).size());
      assertEquals(rootSpanLocal, writer.get(1).get(0));
      assertEquals(2, writer.traceCount.get());
    } finally {
      quickTracer.close();
    }
  }

  @Test
  void partialFlushWithRootSpanClosedLast() throws Exception {
    injectSysConfig(PARTIAL_FLUSH_MIN_SPANS, "2");
    CoreTracer quickTracer = tracerBuilder().writer(writer).build();
    try {
      DDSpan rootSpanLocal = (DDSpan) quickTracer.buildSpan("root").start();
      PendingTrace trace = (PendingTrace) rootSpanLocal.context().getTraceCollector();
      DDSpan child1 =
          (DDSpan) quickTracer.buildSpan("child1").asChildOf(rootSpanLocal.context()).start();
      DDSpan child2 =
          (DDSpan) quickTracer.buildSpan("child2").asChildOf(rootSpanLocal.context()).start();

      assertEquals(3, trace.getPendingReferenceCount());

      child1.finish();

      assertEquals(2, trace.getPendingReferenceCount());
      assertEquals(1, trace.getSpans().size());
      assertEquals(
          child1, ((java.util.concurrent.ConcurrentLinkedDeque<DDSpan>) trace.getSpans()).peek());
      assertEquals(0, writer.size());
      assertEquals(0, writer.traceCount.get());

      child2.finish();
      writer.waitForTraces(1);

      assertEquals(1, trace.getPendingReferenceCount());
      assertTrue(trace.getSpans().isEmpty());
      assertEquals(1, writer.size());
      assertEquals(2, writer.get(0).size());
      assertTrue(writer.get(0).contains(child2));
      assertTrue(writer.get(0).contains(child1));
      assertEquals(1, writer.traceCount.get());

      rootSpanLocal.finish();
      writer.waitForTraces(2);

      assertEquals(0, trace.getPendingReferenceCount());
      assertTrue(trace.getSpans().isEmpty());
      assertEquals(2, writer.size());
      assertEquals(1, writer.get(1).size());
      assertEquals(rootSpanLocal, writer.get(1).get(0));
      assertEquals(2, writer.traceCount.get());
    } finally {
      quickTracer.close();
    }
  }

  @ParameterizedTest
  @MethodSource("partialFlushConcurrencyTestArguments")
  void partialFlushConcurrencyTest(int threadCount, int spanCount) throws Exception {
    // reduce logging noise
    Logger logger = (Logger) LoggerFactory.getLogger("datadog.trace");
    Level previousLevel = logger.getLevel();
    logger.setLevel(Level.OFF);

    try {
      CountDownLatch latch = new CountDownLatch(1);
      DDSpan rootSpanLocal = (DDSpan) tracer.buildSpan("test", "root").start();
      PendingTrace traceCollectorLocal = (PendingTrace) rootSpanLocal.context().getTraceCollector();
      List<Throwable> exceptions = new ArrayList<>();
      List<Thread> threads = new ArrayList<>();
      for (int t = 0; t < threadCount; t++) {
        Thread thread =
            new Thread(
                () -> {
                  try {
                    latch.await();
                    List<DDSpan> spans = new ArrayList<>();
                    for (int s = 0; s < spanCount; s++) {
                      spans.add(
                          (DDSpan) tracer.startSpan("test", "child", rootSpanLocal.context()));
                    }
                    for (DDSpan span : spans) {
                      span.finish();
                    }
                  } catch (Throwable ex) {
                    synchronized (exceptions) {
                      exceptions.add(ex);
                    }
                  }
                });
        thread.start();
        threads.add(thread);
      }

      // Finish root span so other spans are queued automatically
      rootSpanLocal.finish();

      writer.waitForTraces(1);

      latch.countDown();
      for (Thread thread : threads) {
        thread.join();
      }
      traceCollectorLocal.getPendingTraceBuffer().flush();

      assertTrue(exceptions.isEmpty());
      assertEquals(0, traceCollectorLocal.getPendingReferenceCount());
      int totalSpans = 0;
      for (List<DDSpan> trace : writer) {
        totalSpans += trace.size();
      }
      assertEquals(threadCount * spanCount + 1, totalSpans);
    } finally {
      logger.setLevel(previousLevel);
    }
  }

  static Stream<Arguments> partialFlushConcurrencyTestArguments() {
    return Stream.of(
        Arguments.of(1, 1),
        Arguments.of(2, 1),
        Arguments.of(1, 2),
        // Sufficiently large to fill the buffer:
        Arguments.of(5, 2000),
        Arguments.of(10, 1000),
        Arguments.of(50, 500));
  }
}
