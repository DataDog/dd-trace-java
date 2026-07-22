package com.datadog.featureflag;

import static com.datadog.featureflag.FlagEvaluationTestSupport.JSON_MAP;
import static com.datadog.featureflag.FlagEvaluationTestSupport.buildTestWriter;
import static com.datadog.featureflag.FlagEvaluationTestSupport.cfg;
import static com.datadog.featureflag.FlagEvaluationTestSupport.clearCoreMetrics;
import static com.datadog.featureflag.FlagEvaluationTestSupport.event;
import static com.datadog.featureflag.FlagEvaluationTestSupport.eventForFlag;
import static com.datadog.featureflag.FlagEvaluationTestSupport.flushAndCaptureJson;
import static com.datadog.featureflag.FlagEvaluationTestSupport.metricSum;
import static com.datadog.featureflag.FlagEvaluationTestSupport.repeat;
import static com.datadog.featureflag.FlagEvaluationTestSupport.simpleEvent;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.intake.Intake;
import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlagEvaluationWriterImplTest {

  @BeforeEach
  void clearCoreMetricsBefore() {
    clearCoreMetrics();
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @AfterEach
  void clearCoreMetricsAfter() {
    clearCoreMetrics();
    FeatureFlaggingGateway.setFlagEvalWriter(null);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @Test
  void degradedCapOverflowTelemetryIsEmittedOnFlush() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);

    setup.handler.addDroppedDegradedOverflowForTest(3);
    setup.handler.flush();

    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        3,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationWriterImpl.DROP_REASON_DEGRADED_CAP));
  }

  @Test
  void publicConstructorAndContextHelpersDelegateToSharedImplementations() {
    final datadog.trace.api.Config config = cfg();
    when(config.getEnv()).thenReturn("prod");
    when(config.getVersion()).thenReturn("1.2.3");
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(new SharedCommunicationObjects(true), config);
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("b", "2");
    attrs.put("a", "1");

    final Map<String, Object> pruned = FlagEvaluationWriterImpl.pruneContext(attrs);

    assertEquals(2, pruned.size());
    assertEquals(
        FlagEvaluationAggregator.canonicalContextKey(pruned),
        FlagEvaluationWriterImpl.canonicalContextKey(pruned));
    writer.close();
  }

  @Test
  void startRegistersWriterAndCloseDeregistersIt() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    writer.start();
    assertEquals(writer, FeatureFlaggingGateway.getFlagEvalWriter());

    writer.close();
    writer.close();
    writer.start();

    assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
  }

  @Test
  void queueOverflowIncrementsObservableDropCounter() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(2, 10L, TimeUnit.SECONDS, factory, cfg());

    for (int i = 0; i < 100; i++) {
      writer.enqueue(simpleEvent("of-flag", "on"));
    }

    assertTrue(writer.droppedQueueOverflow() > 0);
    final long queueDrops = writer.droppedQueueOverflow();
    writer.flushForTest();
    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        queueDrops,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationWriterImpl.DROP_REASON_QUEUE_OVERFLOW));
  }

  @Test
  void enqueueAfterCloseIsDroppedAndCounted() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    writer.close();
    writer.enqueue(simpleEvent("closed-flag", "on"));

    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        1,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationWriterImpl.DROP_REASON_CLOSED));
    assertNull(writer.pollQueuedEventForTest());
  }

  @Test
  void enqueueDisabledDropsWithoutQueueingOrCountingClosedDrop() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
    writer.enqueue(simpleEvent("disabled-flag", "on"));

    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        0,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationWriterImpl.DROP_REASON_CLOSED));
    assertNull(writer.pollQueuedEventForTest());
  }

  @Test
  void enqueueRechecksEnabledStateAfterTakingLifecycleLock() throws Exception {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());
    final Thread enqueuer = new Thread(() -> writer.enqueue(simpleEvent("race-flag", "on")));

    final Object lifecycleLock = lifecycleLock(writer);
    try {
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
      synchronized (lifecycleLock) {
        enqueuer.start();
        awaitThreadState(enqueuer, Thread.State.BLOCKED);
        FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
      }
      enqueuer.join(TimeUnit.SECONDS.toMillis(5));
      assertTrue(!enqueuer.isAlive());
    } finally {
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    }

    assertNull(writer.pollQueuedEventForTest());
  }

  @Test
  void enqueueIgnoresNullEvent() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    writer.enqueue(null);

    assertNull(writer.pollQueuedEventForTest());
    assertEquals(0, writer.droppedQueueOverflow());
  }

  @Test
  void enqueueDoesNotAggregateOnTheCallingThread() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    writer.enqueue(simpleEvent("g2-flag", "on"));
    writer.enqueue(simpleEvent("g2-flag", "on"));

    assertEquals(0, writer.aggregatorFullTierSizeForTest());
    assertEquals(0, writer.droppedQueueOverflow());
  }

  @Test
  void enqueueDoesNotResolveContextBeforeBuffering() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());
    final AtomicInteger resolutions = new AtomicInteger();
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("tier", "gold");

    writer.enqueue(
        new FlagEvalEvent(
            "lazy-flag",
            "on",
            "alloc1",
            "user-1",
            null,
            1000L,
            () -> {
              resolutions.incrementAndGet();
              return attrs;
            }));

    final FlagEvalEvent queued = writer.pollQueuedEventForTest();
    assertNotNull(queued);
    assertTrue(queued.attrs.isEmpty());
    assertEquals(0, resolutions.get());
    assertEquals("gold", queued.contextAttributes().get("tier"));
    assertEquals(1, resolutions.get());
  }

  @Test
  void contextMaterializationFailureDropsSingleEvent() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);
    setup.handler.add(
        new FlagEvalEvent(
            "bad-context",
            "on",
            "alloc1",
            "user-1",
            null,
            1000L,
            () -> {
              throw new IllegalArgumentException("bad context");
            }));

    setup.handler.drainAndAggregate();

    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        1,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationWriterImpl.DROP_REASON_CONTEXT_ERROR));
    assertEquals(0, setup.handler.fullTierSizeForTest());
  }

  @Test
  void handlerRunFailsFastWhenEvpProxyIsUnavailable() {
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    final FlagEvaluationWriterImpl.SerializingHandlerForTest handler =
        FlagEvaluationWriterImpl.createHandlerForTest(factory, context());

    assertThrows(IllegalArgumentException.class, handler::run);
  }

  @Test
  void flushIfNecessarySkipsEmptyStateAndWaitsForInterval() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);

    setup.handler.flushIfNecessary();
    setup.handler.add(simpleEvent("pending-flag", "on"));
    setup.handler.drainAndAggregate();
    setup.handler.flushIfNecessary();

    assertEquals(1, setup.handler.fullTierSizeForTest());
  }

  @Test
  void flushIfNecessaryDoesNotReturnEarlyWhenOnlyQueueDropsArePending() {
    final AtomicLong queueDrops = new AtomicLong(1);
    final FlagEvaluationWriterImpl.FlagEvaluationSerializingHandler handler =
        new FlagEvaluationWriterImpl.FlagEvaluationSerializingHandler(
            mock(BackendApiFactory.class),
            Queues.mpscBlockingConsumerArrayQueue(16),
            Long.MAX_VALUE,
            TimeUnit.NANOSECONDS,
            context(),
            queueDrops,
            () -> {});

    handler.flushIfNecessary();

    assertEquals(1, queueDrops.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  void workerHandlesEmptyPolls() throws Exception {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final MessagePassingBlockingQueue<FlagEvalEvent> queue =
        mock(MessagePassingBlockingQueue.class);
    when(queue.poll(100, TimeUnit.MILLISECONDS))
        .thenAnswer(
            invocation -> {
              Thread.currentThread().interrupt();
              return null;
            });
    final FlagEvaluationWriterImpl.FlagEvaluationSerializingHandler handler =
        new FlagEvaluationWriterImpl.FlagEvaluationSerializingHandler(
            factory,
            queue,
            Long.MAX_VALUE,
            TimeUnit.NANOSECONDS,
            context(),
            new AtomicLong(0),
            () -> {});

    handler.run();

    assertTrue(Thread.interrupted());
  }

  @Test
  void degradedBucketsAreSerializedWithoutTargetingKeyOrContext() throws Exception {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);
    setup.handler.addDegradedBucketForTest("degraded-flag", "on", "alloc1", null, 1000L);

    final Map<String, Object> json = flushAndCaptureJson(setup);

    final Map<String, Object> ev = eventForFlag(json, "degraded-flag");
    assertNotNull(ev);
    assertNull(ev.get("targeting_key"));
    assertNull(ev.get("context"));
  }

  @Test
  void testHandlerCanSimulateAndClearDegradedTierAtCap() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);

    setup.handler.simulateDegradedTierAtCap();
    setup.handler.clearAggregationForTest();
    setup.handler.add(simpleEvent("after-clear", "on"));
    setup.handler.drainAndAggregate();

    assertEquals(1, setup.handler.fullTierSizeForTest());
  }

  @Test
  void payloadLimitDropsAreCountedOnFlush() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp, 128);
    setup.handler.add(event(repeat('f', 512), "on", "alloc1", "user-1", 1000L, emptyMap()));

    setup.handler.drainAndAggregate();
    setup.handler.flush();

    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        1,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationWriterImpl.DROP_REASON_PAYLOAD_LIMIT));
  }

  @Test
  void closeDrainsAndFinalFlushesQueuedEvents() throws Exception {
    final java.util.concurrent.CountDownLatch posted = new java.util.concurrent.CountDownLatch(1);
    final RequestBody[] captured = {null};
    final BackendApi mockEvp = mock(BackendApi.class);
    when(mockEvp.post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false)))
        .thenAnswer(
            inv -> {
              captured[0] = inv.getArgument(1);
              posted.countDown();
              return null;
            });
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(
            64, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS, factory, cfg());

    writer.startForTest();
    writer.enqueue(simpleEvent("shutdown-flag", "on"));
    writer.close();

    assertTrue(posted.await(5, TimeUnit.SECONDS));
    assertNotNull(captured[0]);
    final Buffer buf = new Buffer();
    captured[0].writeTo(buf);
    final Map<String, Object> json = JSON_MAP.fromJson(buf.readUtf8());
    assertNotNull(eventForFlag(json, "shutdown-flag"));
  }

  @Test
  void continuousTrafficFlushesWithoutWaitingForIdle() throws Exception {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(1 << 12, 1, TimeUnit.MILLISECONDS, factory, cfg());

    writer.startForTest();
    boolean posted = false;
    try {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() < deadline) {
        writer.enqueue(simpleEvent("busy-flag", "on"));
        try {
          verify(mockEvp, atLeastOnce())
              .post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false));
          posted = true;
          break;
        } catch (AssertionError ignored) {
          // Keep the worker busy until the deadline.
        }
      }
    } finally {
      writer.close();
    }

    assertTrue(posted);
  }

  @Test
  void flushPostsToFlagevaluationEndpoint() throws Exception {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);

    setup.handler.add(event("flag-f", "on", "alloc1", "user-1", 1000L, emptyMap()));
    setup.handler.drainAndAggregate();
    setup.handler.flush();

    verify(setup.factory).createBackendApi(eq(Intake.EVENT_PLATFORM), isNull(), eq(false));
    verify(mockEvp).post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false));
  }

  @Test
  void splitPostFailureDoesNotRetryAlreadySentPayloads() throws Exception {
    final int limit = 1_100;
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp, limit);
    final AtomicInteger posts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (posts.incrementAndGet() == 2) {
                throw new IOException("boom");
              }
              return null;
            })
        .when(mockEvp)
        .post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false));

    for (int i = 0; i < 4; i++) {
      final Map<String, Object> attrs = new HashMap<>();
      attrs.put("payload", repeat('x', 180));
      setup.handler.add(event("split-failure-" + i, "on", "alloc1", "user-" + i, 1000L, attrs));
    }

    setup.handler.drainAndAggregate();
    setup.handler.flush();
    assertEquals(2, posts.get());
    setup.handler.flush();
    assertEquals(2, posts.get());
  }

  private static Object lifecycleLock(final FlagEvaluationWriterImpl writer) throws Exception {
    final Field field = FlagEvaluationWriterImpl.class.getDeclaredField("lifecycleLock");
    field.setAccessible(true);
    return field.get(writer);
  }

  private static void awaitThreadState(final Thread thread, final Thread.State state)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.getState() != state && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(state, thread.getState());
  }

  private static Map<String, String> context() {
    final Map<String, String> context = new HashMap<>();
    context.put("service", "test-service");
    return context;
  }
}
