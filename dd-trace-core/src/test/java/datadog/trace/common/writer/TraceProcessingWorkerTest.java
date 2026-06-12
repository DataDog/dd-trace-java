package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class TraceProcessingWorkerTest extends DDJavaSpecification {

  // -------------------------------------------------------------------------
  // Helper: flush-counting payload dispatcher
  // -------------------------------------------------------------------------

  private PayloadDispatcherImpl flushCountingPayloadDispatcher(AtomicInteger flushCounter) {
    PayloadDispatcherImpl dispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            inv -> {
              flushCounter.incrementAndGet();
              return null;
            })
        .when(dispatcher)
        .flush();
    return dispatcher;
  }

  // -------------------------------------------------------------------------
  // Test 1: heartbeats should be triggered automatically when enabled
  // -------------------------------------------------------------------------

  @Test
  void testHeartbeatsShouldBeTriggeredAutomaticallyWhenEnabled() throws Exception {
    AtomicInteger flushCount = new AtomicInteger();
    TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            mock(HealthMetrics.class),
            flushCountingPayloadDispatcher(flushCount),
            () -> false,
            FAST_LANE,
            1,
            TimeUnit.NANOSECONDS, // stop heartbeats from being throttled
            null);

    // processor is started
    worker.start();

    try {
      // heartbeat occurs automatically
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        if (flushCount.get() > 0) break;
        Thread.sleep(50);
      }
      assertTrue(flushCount.get() > 0);
    } finally {
      // cleanup
      worker.close();
    }
  }

  // -------------------------------------------------------------------------
  // Test 2: heartbeats should occur at least once per second when not throttled
  // -------------------------------------------------------------------------

  @Test
  void testHeartbeatsShouldOccurAtLeastOncePerSecondWhenNotThrottled() throws Exception {
    AtomicInteger flushCount = new AtomicInteger();
    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            mock(HealthMetrics.class),
            flushCountingPayloadDispatcher(flushCount),
            () -> false,
            FAST_LANE,
            1,
            TimeUnit.NANOSECONDS, // stop heartbeats from being throttled
            null)) {

      // processor is started
      worker.start();

      // heartbeat occurs automatically approximately once per second
      // wait 1 second initial delay, then poll up to 1 second
      Thread.sleep(1000);
      long deadline = System.currentTimeMillis() + 1000;
      while (System.currentTimeMillis() < deadline) {
        if (flushCount.get() > 1) break;
        Thread.sleep(50);
      }
      assertTrue(flushCount.get() > 1);
    }
  }

  // -------------------------------------------------------------------------
  // Test 3: a flush should clear the primary queue
  // -------------------------------------------------------------------------

  @Test
  void testAFlushShouldClearThePrimaryQueue() {
    AtomicInteger flushCount = new AtomicInteger();
    // prevent heartbeats from helping the flush happen

    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            mock(HealthMetrics.class),
            flushCountingPayloadDispatcher(flushCount),
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS, // prevent heartbeats from helping the flush happen
            null)) {
      // there is pending work it is completed before a flush
      // processing this span will throw an exception, but it should be caught
      // and not disrupt the flush
      List<DDSpan> trace = Collections.singletonList(mock(DDSpan.class));
      worker.getPrimaryQueue().offer(trace);
      worker.start();
      boolean flushed = worker.flush(10, TimeUnit.SECONDS);

      // the flush succeeds, triggers a dispatch, and the queue is empty
      assertTrue(flushed);
      assertEquals(1, flushCount.get());
      assertTrue(worker.getPrimaryQueue().isEmpty());
    }
  }

  // -------------------------------------------------------------------------
  // Test 4: should report failure if serialization fails
  // -------------------------------------------------------------------------

  @TableTest({
    "scenario     | priority                     ",
    "sampler drop | PrioritySampling.SAMPLER_DROP",
    "user drop    | PrioritySampling.USER_DROP   ",
    "sampler keep | PrioritySampling.SAMPLER_KEEP",
    "user keep    | PrioritySampling.USER_KEEP   ",
    "unset        | PrioritySampling.UNSET       "
  })
  void testShouldReportFailureIfSerializationFails(
      @ConvertWith(PrioritySamplingConverter.class) int priority) throws Exception {
    Throwable theError = new IllegalStateException("thrown by test");
    PayloadDispatcherImpl throwingDispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            inv -> {
              throw theError;
            })
        .when(throwingDispatcher)
        .addTrace(any());

    AtomicInteger errorReported = new AtomicInteger();
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    // do this manually with a counter, despite mockito's lovely syntactical sugar so we don't have
    // a race condition induced flaky test. All we care about is that an error was reported and that
    // it was the right one
    doAnswer(
            inv -> {
              errorReported.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFailedSerialize(any(), any());

    // prevent heartbeats from helping the flush happen

    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            throwingDispatcher,
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS, // prevent heartbeats from helping the flush happen
            null)) {
      worker.start();
      // a trace is processed but can't be passed on
      worker.publish(mock(DDSpan.class), priority, Collections.singletonList(mock(DDSpan.class)));

      // the error is reported to the monitor
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        if (errorReported.get() == 1) break;
        Thread.sleep(50);
      }
      assertEquals(1, errorReported.get());
    }
  }

  // -------------------------------------------------------------------------
  // Test 5: trace should be post-processed
  // -------------------------------------------------------------------------

  @Test
  void testTraceShouldBePostProcessed() throws Exception {
    AtomicInteger acceptedCount = new AtomicInteger();
    PayloadDispatcherImpl countingDispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            inv -> {
              acceptedCount.getAndIncrement();
              return null;
            })
        .when(countingDispatcher)
        .addTrace(any());
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    // Create real DDSpan instances via reflection (DDSpan.create is package-private)
    Method createMethod =
        DDSpan.class.getDeclaredMethod(
            "create", String.class, long.class, DDSpanContext.class, List.class);
    createMethod.setAccessible(true);

    DDSpanContext ctx1 = mock(DDSpanContext.class);
    PendingTrace pendingTrace1 = mock(PendingTrace.class);
    when(ctx1.getTraceCollector()).thenReturn(pendingTrace1);
    when(pendingTrace1.getCurrentTimeNano()).thenReturn(0L);

    DDSpanContext ctx2 = mock(DDSpanContext.class);
    PendingTrace pendingTrace2 = mock(PendingTrace.class);
    when(ctx2.getTraceCollector()).thenReturn(pendingTrace2);
    when(pendingTrace2.getCurrentTimeNano()).thenReturn(0L);

    DDSpan span1 = (DDSpan) createMethod.invoke(null, "test", 0L, ctx1, Collections.emptyList());
    DDSpan span2 = (DDSpan) createMethod.invoke(null, "test", 0L, ctx2, Collections.emptyList());

    AtomicBoolean processedSpan1 = new AtomicBoolean(false);
    AtomicBoolean processedSpan2 = new AtomicBoolean(false);

    SpanPostProcessor mockProcessor = mock(SpanPostProcessor.class);
    doAnswer(
            inv -> {
              Object spanArg = inv.getArgument(0);
              if (spanArg == span1) processedSpan1.set(true);
              if (spanArg == span2) processedSpan2.set(true);
              return null;
            })
        .when(mockProcessor)
        .process(any(), any());

    SpanPostProcessor.Holder.INSTANCE = mockProcessor;

    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            countingDispatcher,
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS,
            null)) {
      worker.start();
      // traces are submitted
      List<DDSpan> trace = new ArrayList<>();
      trace.add(span1);
      trace.add(span2);
      worker.publish(span1, SAMPLER_KEEP, trace);
      worker.publish(span2, SAMPLER_KEEP, trace);

      // traces are passed through unless rejected on submission
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        if (processedSpan1.get() && processedSpan2.get()) break;
        Thread.sleep(50);
      }
      assertTrue(processedSpan1.get());
      assertTrue(processedSpan2.get());
    } finally {
      SpanPostProcessor.Holder.INSTANCE = SpanPostProcessor.Holder.NOOP;
    }
  }

  // -------------------------------------------------------------------------
  // Test 6: traces should be processed
  // -------------------------------------------------------------------------

  @TableTest({
    "scenario          | priority                      | traceCount",
    "sampler drop x1   | PrioritySampling.SAMPLER_DROP | 1         ",
    "user drop x1      | PrioritySampling.USER_DROP    | 1         ",
    "sampler keep x1   | PrioritySampling.SAMPLER_KEEP | 1         ",
    "user keep x1      | PrioritySampling.USER_KEEP    | 1         ",
    "unset x1          | PrioritySampling.UNSET        | 1         ",
    "sampler drop x10  | PrioritySampling.SAMPLER_DROP | 10        ",
    "user drop x10     | PrioritySampling.USER_DROP    | 10        ",
    "sampler keep x10  | PrioritySampling.SAMPLER_KEEP | 10        ",
    "user keep x10     | PrioritySampling.USER_KEEP    | 10        ",
    "unset x10         | PrioritySampling.UNSET        | 10        ",
    "sampler drop x20  | PrioritySampling.SAMPLER_DROP | 20        ",
    "user drop x20     | PrioritySampling.USER_DROP    | 20        ",
    "sampler keep x20  | PrioritySampling.SAMPLER_KEEP | 20        ",
    "user keep x20     | PrioritySampling.USER_KEEP    | 20        ",
    "unset x20         | PrioritySampling.UNSET        | 20        ",
    "sampler drop x100 | PrioritySampling.SAMPLER_DROP | 100       ",
    "user drop x100    | PrioritySampling.USER_DROP    | 100       ",
    "sampler keep x100 | PrioritySampling.SAMPLER_KEEP | 100       ",
    "user keep x100    | PrioritySampling.USER_KEEP    | 100       ",
    "unset x100        | PrioritySampling.UNSET        | 100       "
  })
  void testTracesShouldBeProcessed(
      @ConvertWith(PrioritySamplingConverter.class) int priority, int traceCount) throws Exception {
    AtomicInteger acceptedCount = new AtomicInteger();
    PayloadDispatcherImpl countingDispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            inv -> {
              acceptedCount.getAndIncrement();
              return null;
            })
        .when(countingDispatcher)
        .addTrace(any());
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    // prevent heartbeats from helping the flush happen

    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            countingDispatcher,
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS, // prevent heartbeats from helping the flush happen
            null)) {
      worker.start();
      // traces are submitted
      int submitted = 0;
      for (int i = 0; i < traceCount; ++i) {
        PublishResult publishResult =
            worker.publish(
                mock(DDSpan.class), priority, Collections.singletonList(mock(DDSpan.class)));
        submitted += publishResult == ENQUEUED_FOR_SERIALIZATION ? 1 : 0;
      }

      // traces are passed through unless rejected on submission
      int expectedSubmitted = submitted;
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        if (expectedSubmitted == acceptedCount.get()) break;
        Thread.sleep(50);
      }
      assertEquals(expectedSubmitted, acceptedCount.get());
    }
    // cleanup
  }

  // -------------------------------------------------------------------------
  // Test 7: flush of full queue after worker thread stopped will not flush but will return
  // -------------------------------------------------------------------------

  @Test
  void testFlushOfFullQueueAfterWorkerThreadStoppedWillNotFlushButWillReturn() {
    PayloadDispatcherImpl countingDispatcher = mock(PayloadDispatcherImpl.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            countingDispatcher,
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS,
            null);
    worker.start();
    worker.close();

    while (worker.getPrimaryQueue().offer(Collections.singletonList(mock(DDSpan.class)))) {
      // fill the queue
    }

    boolean flushed = worker.flush(1, TimeUnit.SECONDS);
    assertFalse(flushed);
  }

  // -------------------------------------------------------------------------
  // Test 8: send unsampled traces - dropping policy is active
  // -------------------------------------------------------------------------

  @TableTest({
    "scenario                       | priority                      | traceCount | acceptedTraces | acceptedSpans | sampledSingleSpans | spanCount",
    "sampler drop 1 trace 1 span    | PrioritySampling.SAMPLER_DROP | 1          | 1              | 1             | 1                  | 1        ",
    "user drop 1 trace 2 spans      | PrioritySampling.USER_DROP    | 1          | 1              | 1             | 1                  | 2        ",
    "sampler drop 1 trace 3 spans   | PrioritySampling.SAMPLER_DROP | 1          | 1              | 2             | 2                  | 3        ",
    "user drop 1 trace 4 spans      | PrioritySampling.USER_DROP    | 1          | 1              | 2             | 2                  | 4        ",
    "sampler drop 1 trace 5 spans   | PrioritySampling.SAMPLER_DROP | 1          | 1              | 3             | 3                  | 5        ",
    "user drop 2 traces 1 span      | PrioritySampling.USER_DROP    | 2          | 1              | 1             | 1                  | 1        ",
    "sampler drop 2 traces 2 spans  | PrioritySampling.SAMPLER_DROP | 2          | 2              | 2             | 2                  | 2        ",
    "user drop 2 traces 3 spans     | PrioritySampling.USER_DROP    | 2          | 2              | 3             | 3                  | 3        ",
    "sampler drop 2 traces 4 spans  | PrioritySampling.SAMPLER_DROP | 2          | 2              | 4             | 4                  | 4        ",
    "user drop 2 traces 5 spans     | PrioritySampling.USER_DROP    | 2          | 2              | 5             | 5                  | 5        ",
    "sampler drop 10 traces 1 span  | PrioritySampling.SAMPLER_DROP | 10         | 5              | 5             | 5                  | 1        ",
    "user drop 10 traces 2 spans    | PrioritySampling.USER_DROP    | 10         | 10             | 10            | 10                 | 2        ",
    "sampler drop 10 traces 3 spans | PrioritySampling.SAMPLER_DROP | 10         | 10             | 15            | 15                 | 3        ",
    "user drop 10 traces 4 spans    | PrioritySampling.USER_DROP    | 10         | 10             | 20            | 20                 | 4        ",
    "sampler drop 10 traces 5 spans | PrioritySampling.SAMPLER_DROP | 10         | 10             | 25            | 25                 | 5        ",
    "sampler keep 1 trace 1 span    | PrioritySampling.SAMPLER_KEEP | 1          | 1              | 1             | 0                  | 1        ",
    "user keep 1 trace 2 spans      | PrioritySampling.USER_KEEP    | 1          | 1              | 2             | 0                  | 2        ",
    "sampler keep 1 trace 3 spans   | PrioritySampling.SAMPLER_KEEP | 1          | 1              | 3             | 0                  | 3        ",
    "user keep 1 trace 4 spans      | PrioritySampling.USER_KEEP    | 1          | 1              | 4             | 0                  | 4        ",
    "sampler keep 1 trace 5 spans   | PrioritySampling.SAMPLER_KEEP | 1          | 1              | 5             | 0                  | 5        ",
    "user keep 2 traces 1 span      | PrioritySampling.USER_KEEP    | 2          | 2              | 2             | 0                  | 1        ",
    "sampler keep 2 traces 2 spans  | PrioritySampling.SAMPLER_KEEP | 2          | 2              | 4             | 0                  | 2        ",
    "user keep 2 traces 3 spans     | PrioritySampling.USER_KEEP    | 2          | 2              | 6             | 0                  | 3        ",
    "sampler keep 2 traces 4 spans  | PrioritySampling.SAMPLER_KEEP | 2          | 2              | 8             | 0                  | 4        ",
    "user keep 2 traces 5 spans     | PrioritySampling.USER_KEEP    | 2          | 2              | 10            | 0                  | 5        ",
    "sampler keep 10 traces 1 span  | PrioritySampling.SAMPLER_KEEP | 10         | 10             | 10            | 0                  | 1        ",
    "user keep 10 traces 2 spans    | PrioritySampling.USER_KEEP    | 10         | 10             | 20            | 0                  | 2        ",
    "sampler keep 10 traces 3 spans | PrioritySampling.SAMPLER_KEEP | 10         | 10             | 30            | 0                  | 3        ",
    "user keep 10 traces 4 spans    | PrioritySampling.USER_KEEP    | 10         | 10             | 40            | 0                  | 4        ",
    "sampler keep 10 traces 5 spans | PrioritySampling.SAMPLER_KEEP | 10         | 10             | 50            | 0                  | 5        "
  })
  void testSendUnsampledTracesDroppingPolicyIsActive(
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      int traceCount,
      int acceptedTraces,
      int acceptedSpans,
      int sampledSingleSpans,
      int spanCount)
      throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    AtomicInteger acceptedCount = new AtomicInteger();
    AtomicInteger acceptedSpanCount = new AtomicInteger();
    PayloadDispatcherImpl countingDispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            inv -> {
              List<?> traceList = inv.getArgument(0);
              acceptedSpanCount.getAndAdd(traceList.size());
              acceptedCount.getAndIncrement();
              return null;
            })
        .when(countingDispatcher)
        .addTrace(any());

    AtomicInteger sampledSpansCount = new AtomicInteger();
    // drop every other span
    SingleSpanSampler singleSpanSampler =
        new SingleSpanSampler() {
          int counter = 0;

          @Override
          public <T extends CoreSpan<T>> boolean setSamplingPriority(T span) {
            if (counter++ % 2 == 0) {
              sampledSpansCount.incrementAndGet();
              return true;
            }
            return false;
          }
        };

    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            countingDispatcher,
            () -> true,
            FAST_LANE,
            100,
            TimeUnit.SECONDS,
            singleSpanSampler)) {
      worker.start();
      // traces are submitted
      for (int i = 0; i < traceCount; ++i) {
        List<DDSpan> trace = new ArrayList<>();
        for (int j = 0; j < spanCount; j++) {
          trace.add(mock(DDSpan.class));
        }
        worker.publish(trace.get(0), priority, trace);
      }

      // traces are passed through unless rejected on submission
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        if (acceptedTraces == acceptedCount.get()
            && acceptedSpans == acceptedSpanCount.get()
            && sampledSingleSpans == sampledSpansCount.get()) break;
        Thread.sleep(50);
      }
      assertEquals(acceptedTraces, acceptedCount.get());
      assertEquals(acceptedSpans, acceptedSpanCount.get());
      assertEquals(sampledSingleSpans, sampledSpansCount.get());
    }
  }

  // -------------------------------------------------------------------------
  // Test 9: send unsampled traces - dropping policy is inactive
  // -------------------------------------------------------------------------

  @TableTest({
    "scenario                       | priority                      | traceCount | expectedChunks | expectedSpans | sampledSingleSpans | spanCount",
    "sampler drop 1 trace 1 span    | PrioritySampling.SAMPLER_DROP | 1          | 1              | 1             | 1                  | 1        ",
    "user drop 1 trace 2 spans      | PrioritySampling.USER_DROP    | 1          | 2              | 2             | 1                  | 2        ",
    "sampler drop 1 trace 3 spans   | PrioritySampling.SAMPLER_DROP | 1          | 2              | 3             | 2                  | 3        ",
    "user drop 1 trace 4 spans      | PrioritySampling.USER_DROP    | 1          | 2              | 4             | 2                  | 4        ",
    "sampler drop 1 trace 5 spans   | PrioritySampling.SAMPLER_DROP | 1          | 2              | 5             | 3                  | 5        ",
    "user drop 2 traces 1 span      | PrioritySampling.USER_DROP    | 2          | 2              | 2             | 1                  | 1        ",
    "sampler drop 2 traces 2 spans  | PrioritySampling.SAMPLER_DROP | 2          | 4              | 4             | 2                  | 2        ",
    "user drop 2 traces 3 spans     | PrioritySampling.USER_DROP    | 2          | 4              | 6             | 3                  | 3        ",
    "sampler drop 2 traces 4 spans  | PrioritySampling.SAMPLER_DROP | 2          | 4              | 8             | 4                  | 4        ",
    "user drop 2 traces 5 spans     | PrioritySampling.USER_DROP    | 2          | 4              | 10            | 5                  | 5        ",
    "user drop 10 traces 1 span     | PrioritySampling.USER_DROP    | 10         | 10             | 10            | 5                  | 1        ",
    "sampler drop 10 traces 2 spans | PrioritySampling.SAMPLER_DROP | 10         | 20             | 20            | 10                 | 2        ",
    "user drop 10 traces 3 spans    | PrioritySampling.USER_DROP    | 10         | 20             | 30            | 15                 | 3        ",
    "sampler drop 10 traces 4 spans | PrioritySampling.SAMPLER_DROP | 10         | 20             | 40            | 20                 | 4        ",
    "user drop 10 traces 5 spans    | PrioritySampling.USER_DROP    | 10         | 20             | 50            | 25                 | 5        ",
    "sampler keep 1 trace 1 span    | PrioritySampling.SAMPLER_KEEP | 1          | 1              | 1             | 0                  | 1        ",
    "user keep 1 trace 2 spans      | PrioritySampling.USER_KEEP    | 1          | 1              | 2             | 0                  | 2        ",
    "sampler keep 1 trace 3 spans   | PrioritySampling.SAMPLER_KEEP | 1          | 1              | 3             | 0                  | 3        ",
    "user keep 1 trace 4 spans      | PrioritySampling.USER_KEEP    | 1          | 1              | 4             | 0                  | 4        ",
    "sampler keep 1 trace 5 spans   | PrioritySampling.SAMPLER_KEEP | 1          | 1              | 5             | 0                  | 5        ",
    "user keep 2 traces 1 span      | PrioritySampling.USER_KEEP    | 2          | 2              | 2             | 0                  | 1        ",
    "sampler keep 2 traces 2 spans  | PrioritySampling.SAMPLER_KEEP | 2          | 2              | 4             | 0                  | 2        ",
    "user keep 2 traces 3 spans     | PrioritySampling.USER_KEEP    | 2          | 2              | 6             | 0                  | 3        ",
    "sampler keep 2 traces 4 spans  | PrioritySampling.SAMPLER_KEEP | 2          | 2              | 8             | 0                  | 4        ",
    "user keep 2 traces 5 spans     | PrioritySampling.USER_KEEP    | 2          | 2              | 10            | 0                  | 5        ",
    "sampler keep 10 traces 1 span  | PrioritySampling.SAMPLER_KEEP | 10         | 10             | 10            | 0                  | 1        ",
    "user keep 10 traces 2 spans    | PrioritySampling.USER_KEEP    | 10         | 10             | 20            | 0                  | 2        ",
    "sampler keep 10 traces 3 spans | PrioritySampling.SAMPLER_KEEP | 10         | 10             | 30            | 0                  | 3        ",
    "user keep 10 traces 4 spans    | PrioritySampling.USER_KEEP    | 10         | 10             | 40            | 0                  | 4        ",
    "sampler keep 10 traces 5 spans | PrioritySampling.SAMPLER_KEEP | 10         | 10             | 50            | 0                  | 5        "
  })
  void testSendUnsampledTracesDroppingPolicyIsInactive(
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      int traceCount,
      int expectedChunks,
      int expectedSpans,
      int sampledSingleSpans,
      int spanCount)
      throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    AtomicInteger chunksCount = new AtomicInteger();
    AtomicInteger spansCount = new AtomicInteger();
    PayloadDispatcherImpl countingDispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            inv -> {
              List<?> traceList = inv.getArgument(0);
              spansCount.getAndAdd(traceList.size());
              chunksCount.getAndIncrement();
              return null;
            })
        .when(countingDispatcher)
        .addTrace(any());

    AtomicInteger sampledSpansCount = new AtomicInteger();
    // drop every other span
    SingleSpanSampler singleSpanSampler =
        new SingleSpanSampler() {
          int counter = 0;

          @Override
          public <T extends CoreSpan<T>> boolean setSamplingPriority(T span) {
            if (counter++ % 2 == 0) {
              sampledSpansCount.incrementAndGet();
              return true;
            }
            return false;
          }
        };

    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            countingDispatcher,
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS,
            singleSpanSampler)) {
      worker.start();
      // traces are submitted
      for (int i = 0; i < traceCount; ++i) {
        List<DDSpan> trace = new ArrayList<>();
        for (int j = 0; j < spanCount; j++) {
          trace.add(mock(DDSpan.class));
        }
        worker.publish(trace.get(0), priority, trace);
      }

      // traces are passed through unless rejected on submission
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        if (expectedChunks == chunksCount.get()
            && expectedSpans == spansCount.get()
            && sampledSingleSpans == sampledSpansCount.get()) break;
        Thread.sleep(50);
      }
      assertEquals(expectedChunks, chunksCount.get());
      assertEquals(expectedSpans, spansCount.get());
      assertEquals(sampledSingleSpans, sampledSpansCount.get());
    }
  }
}
