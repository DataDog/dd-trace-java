package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TraceProcessingWorkerTest {

  private static final long TIMEOUT_MS = 5000;

  private static void eventually(Runnable assertion) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TIMEOUT_MS;
    AssertionError lastError = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        assertion.run();
        return;
      } catch (AssertionError e) {
        lastError = e;
        Thread.sleep(50);
      }
    }
    if (lastError != null) {
      throw lastError;
    }
  }

  private PayloadDispatcherImpl flushCountingPayloadDispatcher(AtomicInteger flushCounter) {
    PayloadDispatcherImpl dispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            invocation -> {
              flushCounter.incrementAndGet();
              return null;
            })
        .when(dispatcher)
        .flush();
    return dispatcher;
  }

  @Test
  void heartbeatsShouldBeTriggeredAutomaticallyWhenEnabled() throws Exception {
    AtomicInteger flushCount = new AtomicInteger();
    TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            mock(HealthMetrics.class),
            flushCountingPayloadDispatcher(flushCount),
            () -> false,
            FAST_LANE,
            1,
            TimeUnit.NANOSECONDS,
            null); // stop heartbeats from being throttled

    try {
      worker.start();

      eventually(() -> assertTrue(flushCount.get() > 0));
    } finally {
      worker.close();
    }
  }

  @Test
  void heartbeatsShouldOccurAtLeastOncePerSecondWhenNotThrottled() throws Exception {
    AtomicInteger flushCount = new AtomicInteger();
    TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            mock(HealthMetrics.class),
            flushCountingPayloadDispatcher(flushCount),
            () -> false,
            FAST_LANE,
            1,
            TimeUnit.NANOSECONDS,
            null); // stop heartbeats from being throttled

    try {
      worker.start();
      Thread.sleep(1000);

      eventually(() -> assertTrue(flushCount.get() > 1));
    } finally {
      worker.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void aFlushShouldClearThePrimaryQueue() throws Exception {
    AtomicInteger flushCount = new AtomicInteger();
    TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            mock(HealthMetrics.class),
            flushCountingPayloadDispatcher(flushCount),
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS,
            null); // prevent heartbeats from helping the flush happen

    try {
      // processing this span will throw an exception, but it should be caught
      // and not disrupt the flush
      List<DDSpan> trace = new ArrayList<>();
      trace.add(mock(DDSpan.class));
      worker.primaryQueue.offer(trace);
      worker.start();
      boolean flushed = worker.flush(10, TimeUnit.SECONDS);

      assertTrue(flushed);
      assertEquals(1, flushCount.get());
      assertTrue(worker.primaryQueue.isEmpty());
    } finally {
      worker.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldReportFailureIfSerializationFails() throws Exception {
    Throwable theError = new IllegalStateException("thrown by test");
    PayloadDispatcherImpl throwingDispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            invocation -> {
              throw theError;
            })
        .when(throwingDispatcher)
        .addTrace(org.mockito.ArgumentMatchers.any());
    AtomicInteger errorReported = new AtomicInteger();
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    doAnswer(
            invocation -> {
              errorReported.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFailedSerialize(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(theError));

    TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            throwingDispatcher,
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS,
            null); // prevent heartbeats from helping the flush happen
    worker.start();

    try {
      for (int priority : new int[] {SAMPLER_DROP, USER_DROP, SAMPLER_KEEP, USER_KEEP, UNSET}) {
        errorReported.set(0);
        List<DDSpan> trace = new ArrayList<>();
        trace.add(mock(DDSpan.class));
        worker.publish(mock(DDSpan.class), priority, trace);

        int finalPriority = priority;
        eventually(() -> assertEquals(1, errorReported.get()));
      }
    } finally {
      worker.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void traceShouldBePostProcessed() throws Exception {
    AtomicInteger acceptedCount = new AtomicInteger();
    PayloadDispatcherImpl countingDispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            invocation -> {
              acceptedCount.getAndIncrement();
              return null;
            })
        .when(countingDispatcher)
        .addTrace(org.mockito.ArgumentMatchers.any());
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    DDSpanContext ctx1 = mock(DDSpanContext.class);
    PendingTrace pt1 = mock(PendingTrace.class);
    when(ctx1.getTraceCollector()).thenReturn(pt1);
    when(pt1.getCurrentTimeNano()).thenReturn(0L);
    DDSpan span1 = DDSpan.create("test", 0, ctx1, null);

    DDSpanContext ctx2 = mock(DDSpanContext.class);
    PendingTrace pt2 = mock(PendingTrace.class);
    when(ctx2.getTraceCollector()).thenReturn(pt2);
    when(pt2.getCurrentTimeNano()).thenReturn(0L);
    DDSpan span2 = DDSpan.create("test", 0, ctx2, null);

    boolean[] processedSpan1 = {false};
    boolean[] processedSpan2 = {false};

    SpanPostProcessor mockProcessor = mock(SpanPostProcessor.class);
    doAnswer(
            invocation -> {
              processedSpan1[0] = true;
              return null;
            })
        .when(mockProcessor)
        .process(org.mockito.ArgumentMatchers.eq(span1), org.mockito.ArgumentMatchers.any());
    doAnswer(
            invocation -> {
              processedSpan2[0] = true;
              return null;
            })
        .when(mockProcessor)
        .process(org.mockito.ArgumentMatchers.eq(span2), org.mockito.ArgumentMatchers.any());
    SpanPostProcessor.Holder.INSTANCE = mockProcessor;

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

    try {
      List<DDSpan> trace = Arrays.asList(span1, span2);
      worker.publish(span1, SAMPLER_KEEP, trace);
      worker.publish(span2, SAMPLER_KEEP, trace);

      eventually(
          () -> {
            assertTrue(processedSpan1[0]);
            assertTrue(processedSpan2[0]);
          });
    } finally {
      SpanPostProcessor.Holder.INSTANCE = SpanPostProcessor.Holder.NOOP;
      worker.close();
    }
  }

  static Stream<Arguments> tracesAreProcessedArguments() {
    return Stream.of(
        Arguments.of(SAMPLER_DROP, 1),
        Arguments.of(USER_DROP, 1),
        Arguments.of(SAMPLER_KEEP, 1),
        Arguments.of(USER_KEEP, 1),
        Arguments.of(UNSET, 1),
        Arguments.of(SAMPLER_DROP, 10),
        Arguments.of(USER_DROP, 10),
        Arguments.of(SAMPLER_KEEP, 10),
        Arguments.of(USER_KEEP, 10),
        Arguments.of(UNSET, 10),
        Arguments.of(SAMPLER_DROP, 20),
        Arguments.of(USER_DROP, 20),
        Arguments.of(SAMPLER_KEEP, 20),
        Arguments.of(USER_KEEP, 20),
        Arguments.of(UNSET, 20),
        Arguments.of(SAMPLER_DROP, 100),
        Arguments.of(USER_DROP, 100),
        Arguments.of(SAMPLER_KEEP, 100),
        Arguments.of(USER_KEEP, 100),
        Arguments.of(UNSET, 100));
  }

  @ParameterizedTest
  @MethodSource("tracesAreProcessedArguments")
  @SuppressWarnings("unchecked")
  void tracesAreProcessed(int priority, int traceCount) throws Exception {
    AtomicInteger acceptedCount = new AtomicInteger();
    PayloadDispatcherImpl countingDispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            invocation -> {
              acceptedCount.getAndIncrement();
              return null;
            })
        .when(countingDispatcher)
        .addTrace(org.mockito.ArgumentMatchers.any());
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

    try {
      int submitted = 0;
      for (int i = 0; i < traceCount; ++i) {
        List<DDSpan> trace = new ArrayList<>();
        trace.add(mock(DDSpan.class));
        PublishResult publishResult = worker.publish(mock(DDSpan.class), priority, trace);
        submitted += publishResult == ENQUEUED_FOR_SERIALIZATION ? 1 : 0;
      }

      int finalSubmitted = submitted;
      eventually(() -> assertEquals(finalSubmitted, acceptedCount.get()));
    } finally {
      worker.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void flushOfFullQueueAfterWorkerThreadStoppedWillNotFlushButWillReturn() throws Exception {
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
    while (worker.primaryQueue.offer(new ArrayList<>())) {
      // fill the queue
    }

    boolean flushed = worker.flush(1, TimeUnit.SECONDS);
    assertFalse(flushed);
  }

  static Stream<Arguments> unsampledTracesWithDroppingPolicyArguments() {
    return Stream.of(
        // priority | traceCount | acceptedTraces | acceptedSpans | sampledSingleSpans | spanCount
        Arguments.of(SAMPLER_DROP, 1, 1, 1, 1, 1),
        Arguments.of(USER_DROP, 1, 1, 1, 1, 2),
        Arguments.of(SAMPLER_DROP, 1, 1, 2, 2, 3),
        Arguments.of(USER_DROP, 1, 1, 2, 2, 4),
        Arguments.of(SAMPLER_DROP, 1, 1, 3, 3, 5),
        Arguments.of(USER_DROP, 2, 1, 1, 1, 1),
        Arguments.of(SAMPLER_DROP, 2, 2, 2, 2, 2),
        Arguments.of(USER_DROP, 2, 2, 3, 3, 3),
        Arguments.of(SAMPLER_DROP, 2, 2, 4, 4, 4),
        Arguments.of(USER_DROP, 2, 2, 5, 5, 5),
        Arguments.of(SAMPLER_DROP, 10, 5, 5, 5, 1),
        Arguments.of(USER_DROP, 10, 10, 10, 10, 2),
        Arguments.of(SAMPLER_DROP, 10, 10, 15, 15, 3),
        Arguments.of(USER_DROP, 10, 10, 20, 20, 4),
        Arguments.of(SAMPLER_DROP, 10, 10, 25, 25, 5),
        // do not dispatch kept traces to the single span sampler
        Arguments.of(SAMPLER_KEEP, 1, 1, 1, 0, 1),
        Arguments.of(USER_KEEP, 1, 1, 2, 0, 2),
        Arguments.of(SAMPLER_KEEP, 1, 1, 3, 0, 3),
        Arguments.of(USER_KEEP, 1, 1, 4, 0, 4),
        Arguments.of(SAMPLER_KEEP, 1, 1, 5, 0, 5),
        Arguments.of(USER_KEEP, 2, 2, 2, 0, 1),
        Arguments.of(SAMPLER_KEEP, 2, 2, 4, 0, 2),
        Arguments.of(USER_KEEP, 2, 2, 6, 0, 3),
        Arguments.of(SAMPLER_KEEP, 2, 2, 8, 0, 4),
        Arguments.of(USER_KEEP, 2, 2, 10, 0, 5),
        Arguments.of(SAMPLER_KEEP, 10, 10, 10, 0, 1),
        Arguments.of(USER_KEEP, 10, 10, 20, 0, 2),
        Arguments.of(SAMPLER_KEEP, 10, 10, 30, 0, 3),
        Arguments.of(USER_KEEP, 10, 10, 40, 0, 4),
        Arguments.of(SAMPLER_KEEP, 10, 10, 50, 0, 5));
  }

  @ParameterizedTest
  @MethodSource("unsampledTracesWithDroppingPolicyArguments")
  @SuppressWarnings("unchecked")
  void sendUnsampledTracesToSpanProcessingWorkerWithDroppingPolicyActive(
      int priority,
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
            invocation -> {
              List traceList = invocation.getArgument(0);
              acceptedSpanCount.getAndAdd(traceList.size());
              acceptedCount.getAndIncrement();
              return null;
            })
        .when(countingDispatcher)
        .addTrace(org.mockito.ArgumentMatchers.any());
    AtomicInteger sampledSpansCount = new AtomicInteger();
    SingleSpanSampler singleSpanSampler =
        new SingleSpanSampler() {
          int counter = 0;

          @Override
          public boolean setSamplingPriority(CoreSpan span) {
            if (counter++ % 2 == 0) {
              sampledSpansCount.incrementAndGet();
              return true;
            }
            return false;
          }
        };

    List<DDSpan> trace = new ArrayList<>();
    for (int i = 0; i < spanCount; i++) {
      trace.add(mock(DDSpan.class));
    }

    TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            countingDispatcher,
            () -> true,
            FAST_LANE,
            100,
            TimeUnit.SECONDS,
            singleSpanSampler);
    worker.start();

    try {
      for (int i = 0; i < traceCount; ++i) {
        worker.publish(trace.get(0), priority, trace);
      }

      int finalAcceptedTraces = acceptedTraces;
      int finalAcceptedSpans = acceptedSpans;
      int finalSampledSingleSpans = sampledSingleSpans;
      eventually(
          () -> {
            assertEquals(finalAcceptedTraces, acceptedCount.get());
            assertEquals(finalAcceptedSpans, acceptedSpanCount.get());
            assertEquals(finalSampledSingleSpans, sampledSpansCount.get());
          });
    } finally {
      worker.close();
    }
  }

  static Stream<Arguments> unsampledTracesWithoutDroppingPolicyArguments() {
    return Stream.of(
        // priority | traceCount | expectedChunks | expectedSpans | sampledSingleSpans | spanCount
        Arguments.of(SAMPLER_DROP, 1, 1, 1, 1, 1),
        Arguments.of(USER_DROP, 1, 2, 2, 1, 2),
        Arguments.of(SAMPLER_DROP, 1, 2, 3, 2, 3),
        Arguments.of(USER_DROP, 1, 2, 4, 2, 4),
        Arguments.of(SAMPLER_DROP, 1, 2, 5, 3, 5),
        Arguments.of(USER_DROP, 2, 2, 2, 1, 1),
        Arguments.of(SAMPLER_DROP, 2, 4, 4, 2, 2),
        Arguments.of(USER_DROP, 2, 4, 6, 3, 3),
        Arguments.of(SAMPLER_DROP, 2, 4, 8, 4, 4),
        Arguments.of(USER_DROP, 2, 4, 10, 5, 5),
        Arguments.of(USER_DROP, 10, 10, 10, 5, 1),
        Arguments.of(SAMPLER_DROP, 10, 20, 20, 10, 2),
        Arguments.of(USER_DROP, 10, 20, 30, 15, 3),
        Arguments.of(SAMPLER_DROP, 10, 20, 40, 20, 4),
        Arguments.of(USER_DROP, 10, 20, 50, 25, 5),
        // do not dispatch kept traces to the single span sampler
        Arguments.of(SAMPLER_KEEP, 1, 1, 1, 0, 1),
        Arguments.of(USER_KEEP, 1, 1, 2, 0, 2),
        Arguments.of(SAMPLER_KEEP, 1, 1, 3, 0, 3),
        Arguments.of(USER_KEEP, 1, 1, 4, 0, 4),
        Arguments.of(SAMPLER_KEEP, 1, 1, 5, 0, 5),
        Arguments.of(USER_KEEP, 2, 2, 2, 0, 1),
        Arguments.of(SAMPLER_KEEP, 2, 2, 4, 0, 2),
        Arguments.of(USER_KEEP, 2, 2, 6, 0, 3),
        Arguments.of(SAMPLER_KEEP, 2, 2, 8, 0, 4),
        Arguments.of(USER_KEEP, 2, 2, 10, 0, 5),
        Arguments.of(SAMPLER_KEEP, 10, 10, 10, 0, 1),
        Arguments.of(USER_KEEP, 10, 10, 20, 0, 2),
        Arguments.of(SAMPLER_KEEP, 10, 10, 30, 0, 3),
        Arguments.of(USER_KEEP, 10, 10, 40, 0, 4),
        Arguments.of(SAMPLER_KEEP, 10, 10, 50, 0, 5));
  }

  @ParameterizedTest
  @MethodSource("unsampledTracesWithoutDroppingPolicyArguments")
  @SuppressWarnings("unchecked")
  void sendUnsampledTracesToSpanProcessingWorkerWithDroppingPolicyInactive(
      int priority,
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
            invocation -> {
              List traceList = invocation.getArgument(0);
              spansCount.getAndAdd(traceList.size());
              chunksCount.getAndIncrement();
              return null;
            })
        .when(countingDispatcher)
        .addTrace(org.mockito.ArgumentMatchers.any());
    AtomicInteger sampledSpansCount = new AtomicInteger();
    SingleSpanSampler singleSpanSampler =
        new SingleSpanSampler() {
          int counter = 0;

          @Override
          public boolean setSamplingPriority(CoreSpan span) {
            if (counter++ % 2 == 0) {
              sampledSpansCount.incrementAndGet();
              return true;
            }
            return false;
          }
        };

    List<DDSpan> trace = new ArrayList<>();
    for (int i = 0; i < spanCount; i++) {
      trace.add(mock(DDSpan.class));
    }

    TraceProcessingWorker worker =
        new TraceProcessingWorker(
            10,
            healthMetrics,
            countingDispatcher,
            () -> false,
            FAST_LANE,
            100,
            TimeUnit.SECONDS,
            singleSpanSampler);
    worker.start();

    try {
      for (int i = 0; i < traceCount; ++i) {
        worker.publish(trace.get(0), priority, trace);
      }

      int finalExpectedChunks = expectedChunks;
      int finalExpectedSpans = expectedSpans;
      int finalSampledSingleSpans = sampledSingleSpans;
      eventually(
          () -> {
            assertEquals(finalExpectedChunks, chunksCount.get());
            assertEquals(finalExpectedSpans, spansCount.get());
            assertEquals(finalSampledSingleSpans, sampledSpansCount.get());
          });
    } finally {
      worker.close();
    }
  }
}
