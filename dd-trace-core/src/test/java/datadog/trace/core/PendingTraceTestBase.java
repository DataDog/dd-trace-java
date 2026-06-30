package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.tabletest.junit.TableTest;

public abstract class PendingTraceTestBase extends DDCoreJavaSpecification {

  protected ListWriter writer;
  protected CoreTracer tracer;
  protected DDSpan rootSpan;
  protected PendingTrace traceCollector;

  @BeforeEach
  void setup() throws Exception {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
    rootSpan = (DDSpan) tracer.buildSpan("datadog", "fakeOperation").start();
    traceCollector = (PendingTrace) rootSpan.spanContext().getTraceCollector();

    assertEquals(0, traceCollector.size());
    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertFalse(traceCollector.isRootSpanWritten());
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void singleSpanWrittenWhenFinished() throws InterruptedException, TimeoutException {
    rootSpan.finish();
    writer.waitForTraces(1);

    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(Arrays.asList(Arrays.asList(rootSpan)), new ArrayList<>(writer));
    assertEquals(1, writer.getTraceCount());
  }

  @Test
  void childFinishesBeforeParent() throws InterruptedException, TimeoutException {
    DDSpan child =
        (DDSpan) tracer.buildSpan("datadog", "child").asChildOf(rootSpan.spanContext()).start();

    assertEquals(2, traceCollector.getPendingReferenceCount());

    child.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(Arrays.asList(child), new ArrayList<>(traceCollector.getSpans()));
    assertTrue(writer.isEmpty());

    rootSpan.finish();
    writer.waitForTraces(1);

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(Arrays.asList(Arrays.asList(rootSpan, child)), new ArrayList<>(writer));
    assertEquals(1, writer.getTraceCount());
  }

  @Test
  void parentFinishesBeforeChild() throws InterruptedException, TimeoutException {
    DDSpan child =
        (DDSpan) tracer.buildSpan("datadog", "child").asChildOf(rootSpan.spanContext()).start();

    assertEquals(2, traceCollector.getPendingReferenceCount());

    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(Arrays.asList(rootSpan), new ArrayList<>(traceCollector.getSpans()));
    assertTrue(writer.isEmpty());

    child.finish();
    writer.waitForTraces(1);

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(Arrays.asList(Arrays.asList(child, rootSpan)), new ArrayList<>(writer));
    assertEquals(1, writer.getTraceCount());
  }

  @Test
  void childSpansCreatedAfterWrittenReportedSeparately()
      throws InterruptedException, TimeoutException {
    rootSpan.finish();
    // this shouldn't happen, but it's possible users of the api
    // may incorrectly add spans after the trace is reported.
    // in those cases we should still decrement the pending trace count
    DDSpan childSpan =
        (DDSpan) tracer.buildSpan("datadog", "child").asChildOf(rootSpan.spanContext()).start();
    childSpan.finish();
    writer.waitForTraces(2);

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(
        Arrays.asList(Arrays.asList(rootSpan), Arrays.asList(childSpan)), new ArrayList<>(writer));
  }

  @Test
  void testGetCurrentTimeNano() {
    long diffSeconds =
        Math.abs(
            TimeUnit.NANOSECONDS.toSeconds(traceCollector.getCurrentTimeNano())
                - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
    // Generous 5 seconds to execute this test
    assertTrue(diffSeconds < 5, "Expected time difference < 5 seconds, got: " + diffSeconds);
  }

  @Test
  @WithConfig(key = PARTIAL_FLUSH_MIN_SPANS, value = "2")
  void partialFlush() throws InterruptedException, TimeoutException {
    CoreTracer quickTracer = tracerBuilder().writer(writer).build();
    try {
      DDSpan localRoot = (DDSpan) quickTracer.buildSpan("datadog", "root").start();
      PendingTrace trace = (PendingTrace) localRoot.spanContext().getTraceCollector();
      DDSpan child1 =
          (DDSpan)
              quickTracer.buildSpan("datadog", "child1").asChildOf(localRoot.spanContext()).start();
      DDSpan child2 =
          (DDSpan)
              quickTracer.buildSpan("datadog", "child2").asChildOf(localRoot.spanContext()).start();

      assertEquals(3, trace.getPendingReferenceCount());

      child2.finish();

      assertEquals(2, trace.getPendingReferenceCount());
      assertEquals(Arrays.asList(child2), new ArrayList<>(trace.getSpans()));
      assertTrue(writer.isEmpty());
      assertEquals(0, writer.getTraceCount());

      child1.finish();
      writer.waitForTraces(1);

      assertEquals(1, trace.getPendingReferenceCount());
      assertEquals(Arrays.asList(), new ArrayList<>(trace.getSpans()));
      assertEquals(Arrays.asList(Arrays.asList(child1, child2)), new ArrayList<>(writer));
      assertEquals(1, writer.getTraceCount());

      localRoot.finish();
      writer.waitForTraces(2);

      assertEquals(0, trace.getPendingReferenceCount());
      assertTrue(trace.getSpans().isEmpty());
      assertEquals(
          Arrays.asList(Arrays.asList(child1, child2), Arrays.asList(localRoot)),
          new ArrayList<>(writer));
      assertEquals(2, writer.getTraceCount());
    } finally {
      quickTracer.close();
    }
  }

  @Test
  @WithConfig(key = PARTIAL_FLUSH_MIN_SPANS, value = "2")
  void partialFlushWithRootSpanClosedLast() throws InterruptedException, TimeoutException {
    CoreTracer quickTracer = tracerBuilder().writer(writer).build();
    try {
      DDSpan localRoot = (DDSpan) quickTracer.buildSpan("datadog", "root").start();
      PendingTrace trace = (PendingTrace) localRoot.spanContext().getTraceCollector();
      DDSpan child1 =
          (DDSpan)
              quickTracer.buildSpan("datadog", "child1").asChildOf(localRoot.spanContext()).start();
      DDSpan child2 =
          (DDSpan)
              quickTracer.buildSpan("datadog", "child2").asChildOf(localRoot.spanContext()).start();

      assertEquals(3, trace.getPendingReferenceCount());

      child1.finish();

      assertEquals(2, trace.getPendingReferenceCount());
      assertEquals(Arrays.asList(child1), new ArrayList<>(trace.getSpans()));
      assertTrue(writer.isEmpty());
      assertEquals(0, writer.getTraceCount());

      child2.finish();
      writer.waitForTraces(1);

      assertEquals(1, trace.getPendingReferenceCount());
      assertTrue(trace.getSpans().isEmpty());
      assertEquals(Arrays.asList(Arrays.asList(child2, child1)), new ArrayList<>(writer));
      assertEquals(1, writer.getTraceCount());

      localRoot.finish();
      writer.waitForTraces(2);

      assertEquals(0, trace.getPendingReferenceCount());
      assertTrue(trace.getSpans().isEmpty());
      assertEquals(
          Arrays.asList(Arrays.asList(child2, child1), Arrays.asList(localRoot)),
          new ArrayList<>(writer));
      assertEquals(2, writer.getTraceCount());
    } finally {
      quickTracer.close();
    }
  }

  // spotless:off
  @TableTest({
    "scenario         | threadCount | spanCount",
    "1 thread 1 span  | 1           | 1        ",
    "2 threads 1 span | 2           | 1        ",
    "1 thread 2 spans | 1           | 2        ",
    // Sufficiently large to fill the buffer:
    "5 threads 2000   | 5           | 2000     ",
    "10 threads 1000  | 10          | 1000     ",
    "50 threads 500   | 50          | 500      "
  })
  // spotless:on
  void partialFlushConcurrencyTest(int threadCount, int spanCount)
      throws InterruptedException, TimeoutException {
    // reduce logging noise
    Logger logger = (Logger) LoggerFactory.getLogger("datadog.trace");
    Level previousLevel = logger.getLevel();
    logger.setLevel(Level.OFF);
    try {
      CountDownLatch latch = new CountDownLatch(1);
      DDSpan localRoot = (DDSpan) tracer.buildSpan("test", "root").start();
      PendingTrace localTraceCollector = (PendingTrace) localRoot.spanContext().getTraceCollector();
      List<Throwable> exceptions = new ArrayList<>();

      List<Thread> threads = new ArrayList<>(threadCount);
      for (int t = 0; t < threadCount; t++) {
        Thread thread =
            new Thread(
                () -> {
                  try {
                    latch.await();
                    List<DDSpan> spans = new ArrayList<>(spanCount);
                    for (int s = 0; s < spanCount; s++) {
                      spans.add(
                          (DDSpan) tracer.startSpan("test", "child", localRoot.spanContext()));
                    }
                    for (DDSpan span : spans) {
                      span.finish();
                    }
                  } catch (Throwable ex) {
                    exceptions.add(ex);
                  }
                });
        thread.start();
        threads.add(thread);
      }
      // Finish root span so other spans are queued automatically
      localRoot.finish();
      writer.waitForTraces(1);

      latch.countDown();
      for (Thread thread : threads) {
        thread.join();
      }
      localTraceCollector.getPendingTraceBuffer().flush();

      assertTrue(exceptions.isEmpty(), "Exceptions in worker threads: " + exceptions);
      assertEquals(0, localTraceCollector.getPendingReferenceCount());
      int totalSpans = writer.stream().mapToInt(List::size).sum();
      assertEquals(threadCount * spanCount + 1, totalSpans);
    } finally {
      logger.setLevel(previousLevel);
    }
  }
}
