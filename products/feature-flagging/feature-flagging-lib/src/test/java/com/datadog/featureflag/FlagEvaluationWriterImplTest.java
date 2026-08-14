package com.datadog.featureflag;

import static com.datadog.featureflag.FlagEvaluationTestSupport.JSON_MAP;
import static com.datadog.featureflag.FlagEvaluationTestSupport.buildTestWriter;
import static com.datadog.featureflag.FlagEvaluationTestSupport.cfg;
import static com.datadog.featureflag.FlagEvaluationTestSupport.clearCoreMetrics;
import static com.datadog.featureflag.FlagEvaluationTestSupport.event;
import static com.datadog.featureflag.FlagEvaluationTestSupport.eventForFlag;
import static com.datadog.featureflag.FlagEvaluationTestSupport.flushAndCapture;
import static com.datadog.featureflag.FlagEvaluationTestSupport.flushAndCaptureJson;
import static com.datadog.featureflag.FlagEvaluationTestSupport.metricSum;
import static com.datadog.featureflag.FlagEvaluationTestSupport.repeat;
import static com.datadog.featureflag.FlagEvaluationTestSupport.simpleEvent;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.IntakeApi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.intake.Intake;
import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.test.util.PollingConditions;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlagEvaluationWriterImplTest {

  private static final String DIRECT_FLAG_EVALUATION_ENDPOINT = "/api/v2/flagevaluation";
  private static final String API_KEY = "test-api-key";
  private static final double TIMEOUT_SECONDS = 5;

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
    // Reset the dispatched UFC state so observeFullEvaluationData can't leak into other tests.
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
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
  void startRegistersWriterAndCloseDeregistersIt() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
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
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
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
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
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
  void enqueueDisabledDropsAndCountsAsClosedDrop() {
    // FeatureFlaggingSystem.stop() flips the gate before this writer's close() runs. Producers
    // that race the gate flip must count the drop, otherwise shutdown loss stays invisible.
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
    writer.enqueue(simpleEvent("disabled-flag", "on"));

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
  void closeSweepsAndCountsEventsLeftInTheQueue() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    // The worker is never started, so nothing drains these; close() must account for them rather
    // than leave them silently stranded. Stands in for the narrow window where a lock-free
    // producer offers after the worker's final drain.
    writer.enqueue(simpleEvent("residual-flag-1", "on"));
    writer.enqueue(simpleEvent("residual-flag-2", "on"));
    writer.close();

    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        2,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationWriterImpl.DROP_REASON_CLOSED));
    assertNull(writer.pollQueuedEventForTest());
  }

  @Test
  void enqueueIgnoresNullEvent() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
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
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    writer.enqueue(simpleEvent("g2-flag", "on"));
    writer.enqueue(simpleEvent("g2-flag", "on"));

    assertEquals(0, writer.aggregatorFullTierSizeForTest());
    assertEquals(0, writer.droppedQueueOverflow());
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
            new java.util.concurrent.ConcurrentHashMap<>(),
            () -> {});

    handler.flushIfNecessary();

    assertEquals(1, queueDrops.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  void workerHandlesEmptyPolls() throws Exception {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
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
            new java.util.concurrent.ConcurrentHashMap<>(),
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
  void finalFlushRunsWithoutTheInterruptFlagSet() throws Exception {
    // close() interrupts the worker to break it out of poll(). The final flush does socket I/O,
    // and OkHttp fails fast on an interrupted thread, so the flag must be clear by the time the
    // publisher is called. A mock publisher ignores the flag, so assert on it directly.
    final java.util.concurrent.CountDownLatch posted = new java.util.concurrent.CountDownLatch(1);
    final boolean[] interruptedDuringPost = {true};
    final BackendApi mockEvp = mock(BackendApi.class);
    when(mockEvp.post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false)))
        .thenAnswer(
            inv -> {
              interruptedDuringPost[0] = Thread.currentThread().isInterrupted();
              posted.countDown();
              return null;
            });
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(
            64, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS, factory, cfg());

    writer.startForTest();
    writer.enqueue(simpleEvent("interrupt-flag", "on"));
    writer.close();

    assertTrue(posted.await(5, TimeUnit.SECONDS));
    assertFalse(
        interruptedDuringPost[0],
        "final flush must not run on an interrupted thread; OkHttp fails fast and the drained"
            + " rows are lost without being counted");
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
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
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
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
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

    verify(setup.factory).createBackendApi(Intake.EVENT_PLATFORM, false);
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

  private static final String HASHED_JANE_DOE =
      "sha256_b4698f9b6d186781fa8dc59e533578fa2d8379a46b1cf6db85cda6aa9c99e51b";

  @Test
  void observeFullEvaluationDataTrueEmitsRawTargetingKeyAndContext() throws Exception {
    // Consent travels on the event (snapshotted by the hook at evaluation time); the writer honours
    // it verbatim and never consults the gateway.
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);
    setup.handler.add(piiEvent(true));

    final Map<String, Object> json = flushAndCapture(setup).parsed;

    final Map<String, Object> ev = eventForFlag(json, "pii-flag");
    assertNotNull(ev);
    assertEquals("jane.doe@datadoghq.com", ev.get("targeting_key"));
    final Map<?, ?> ctx = (Map<?, ?>) ev.get("context");
    assertNotNull(ctx);
    final Map<?, ?> evalAttrs = (Map<?, ?>) ctx.get("evaluation");
    assertNotNull(evalAttrs);
    assertEquals("us-east-1", evalAttrs.get("region"));
  }

  @Test
  void observeFullEvaluationDataFalseHashesTargetingKeyAndOmitsContext() throws Exception {
    assertHashedTargetingKeyAndOmittedContext(piiEvent(false));
  }

  @Test
  void flagEvalEventDefaultConsentHashesTargetingKeyAndOmitsContext() throws Exception {
    // An event built without an explicit consent value defaults to the privacy-preserving false, so
    // it must behave exactly like the explicit "false" case. This is the state the hook produces
    // when no UFC has been dispatched (the gateway reports false).
    assertHashedTargetingKeyAndOmittedContext(piiEventDefaultConsent());
  }

  @Test
  void eventConsentFalseStaysHashedEvenWhenGatewayLaterReportsTrue() throws Exception {
    // Regression guard: consent is decided by the value the event carried at evaluation time, never
    // re-read from the gateway at flush. An event evaluated under consent=false must stay hashed
    // even if a later RC update turns the gateway's consent on before the flush drains.
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);
    setup.handler.add(piiEvent(false));

    // Flip the gateway's consent on before both aggregation and flush; the event's evaluation-time
    // snapshot (false) must win at every downstream step, so neither may consult the gateway.
    dispatchObserveFullEvaluationData(true);
    setup.handler.drainAndAggregate();

    final java.util.List<RequestBody> captured = new java.util.ArrayList<>();
    when(mockEvp.post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false)))
        .thenAnswer(
            inv -> {
              captured.add(inv.getArgument(1));
              return null;
            });
    setup.handler.flush();

    assertEquals(1, captured.size());
    final FlagEvaluationTestSupport.CapturedJson json =
        FlagEvaluationTestSupport.readJson(captured.get(0));
    final Map<String, Object> ev = eventForFlag(json.parsed, "pii-flag");
    assertNotNull(ev);
    assertEquals(HASHED_JANE_DOE, ev.get("targeting_key"));
    assertFalse(ev.containsKey("context"));
    assertTrue(json.raw.contains(HASHED_JANE_DOE));
    assertFalse(json.raw.contains("jane.doe@datadoghq.com"));
  }

  @Test
  void eventConsentTrueStaysRawEvenWhenGatewayLaterReportsFalse() throws Exception {
    // Symmetric guard: an event evaluated under consent=true must stay raw even if a later RC
    // update
    // turns the gateway's consent off before aggregation and flush. Together with the false-stays-
    // hashed test this pins that neither aggregation nor flush ever consults the gateway.
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);
    setup.handler.add(piiEvent(true));

    dispatchObserveFullEvaluationData(false);
    setup.handler.drainAndAggregate();

    final java.util.List<RequestBody> captured = new java.util.ArrayList<>();
    when(mockEvp.post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false)))
        .thenAnswer(
            inv -> {
              captured.add(inv.getArgument(1));
              return null;
            });
    setup.handler.flush();

    assertEquals(1, captured.size());
    final Map<String, Object> ev =
        eventForFlag(FlagEvaluationTestSupport.readJson(captured.get(0)).parsed, "pii-flag");
    assertNotNull(ev);
    assertEquals("jane.doe@datadoghq.com", ev.get("targeting_key"));
    final Map<?, ?> ctx = (Map<?, ?>) ev.get("context");
    assertNotNull(ctx);
    assertNotNull(ctx.get("evaluation"));
  }

  @Test
  void consentOffPreservesErrorCodeSignalAndNeverLeaksPiiInErrorMessage() throws Exception {
    // Upstream contract: the hook substitutes the ErrorCode name for the raw exception message
    // under consent-off (see
    // FlagEvalLoggingHookTest#errorMessageReplacedByErrorCodeUnderConsentOff).
    // This wire-level guard pins that a properly-formed consent-off event (a) still surfaces the
    // stable ErrorCode signal for operators and (b) never lets a PII-shaped string escape onto the
    // wire. Mirrors the existing PII guards on the targeting_key axis.
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);
    setup.handler.add(
        new FlagEvalEvent(
            "err-flag",
            null,
            "alloc1",
            "jane.doe@datadoghq.com",
            "TYPE_MISMATCH",
            1000L,
            false,
            emptyMap()));

    final FlagEvaluationTestSupport.CapturedJson captured = flushAndCapture(setup);

    final Map<String, Object> ev = eventForFlag(captured.parsed, "err-flag");
    assertNotNull(ev);
    final Map<?, ?> error = (Map<?, ?>) ev.get("error");
    assertNotNull(error, "error object must be present so operators keep the ErrorCode signal");
    assertEquals("TYPE_MISMATCH", error.get("message"));
    assertEquals(HASHED_JANE_DOE, ev.get("targeting_key"));
    assertFalse(captured.raw.contains("jane.doe@datadoghq.com"));
    assertFalse(
        captured.raw.contains("For input string"),
        "no exception-message-shaped text may reach the wire under consent-off");
  }

  @Test
  void encodeFailureClearsAggregatorSoLaterFlushesRecover() throws Exception {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);

    // Moshi rejects non-finite JSON numbers. A NaN in the context poisons buildPayloads for this
    // bucket. Before the fix, the aggregator kept the bucket and every later flush re-threw.
    final Map<String, Object> poison = new HashMap<>();
    poison.put("bad-number", Double.NaN);
    setup.handler.add(event("poison-flag", "on", "alloc1", "user-1", 1000L, true, poison));
    setup.handler.drainAndAggregate();
    setup.handler.flush();
    verify(mockEvp, org.mockito.Mockito.never())
        .post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false));

    // The bucket must not survive the failed flush. A follow-up healthy event flushes cleanly.
    setup.handler.add(simpleEvent("healthy-flag", "on"));
    setup.handler.drainAndAggregate();
    setup.handler.flush();
    verify(mockEvp).post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false));
  }

  @Test
  void scoConstructorCreatesUsableWriter() {
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(new SharedCommunicationObjects(), cfg());
    writer.enqueue(simpleEvent("sco-flag", "on"));
    assertNotNull(writer.pollQueuedEventForTest());
    writer.close();
  }

  @Test
  void agentlessWritesFlagEvaluationsDirectlyWhenLocalProxyIsUnavailable() throws Exception {
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.prefix(
                            DIRECT_FLAG_EVALUATION_ENDPOINT,
                            api -> api.getResponse().status(200).send("OK"))))) {
      final Config config = cfg();
      when(config.getFeatureFlaggingConfigurationSource())
          .thenReturn(CONFIGURATION_SOURCE_AGENTLESS);
      when(config.getApiKey()).thenReturn(API_KEY);
      final BackendApiFactory backendApiFactory = mock(BackendApiFactory.class);
      final IntakeApi directApi =
          new IntakeApi(
              HttpUrl.get(server.getAddress()).resolve("/api/v2/"),
              API_KEY,
              "123",
              HttpRetryPolicy.Factory.NEVER_RETRY,
              new OkHttpClient.Builder().build(),
              false);
      when(backendApiFactory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false))
          .thenReturn(directApi);
      final FeatureFlagBackendApiFactory featureFlagBackendApiFactory =
          new FeatureFlagBackendApiFactory(
              config, backendApiFactory, FeatureFlagEventType.FLAG_EVALUATION);
      final PollingConditions poll = new PollingConditions(TIMEOUT_SECONDS);

      try (FlagEvaluationWriterImpl writer =
          new FlagEvaluationWriterImpl(
              16, 1, TimeUnit.MILLISECONDS, featureFlagBackendApiFactory, config)) {
        writer.startForTest();
        writer.enqueue(simpleEvent("direct-flag", "on"));

        poll.eventually(
            () -> {
              assertNotNull(server.getLastRequest());
              assertEquals(DIRECT_FLAG_EVALUATION_ENDPOINT, server.getLastRequest().getPath());
              assertEquals(API_KEY, server.getLastRequest().getHeader("dd-api-key"));
              assertNull(server.getLastRequest().getHeader("X-Datadog-EVP-Subdomain"));
              assertTrue(server.getLastRequest().getBody().length > 0);
            });
      }
    }
  }

  @Test
  void countContextTruncatedAccumulatesPerReason() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    writer.countContextTruncated("field_count");
    writer.countContextTruncated("field_count");
    writer.countContextTruncated("field_length");
    writer.flushForTest();

    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        2,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_CONTEXT_TRUNCATED_METRIC,
            "reason:field_count"));
    assertEquals(
        1,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_CONTEXT_TRUNCATED_METRIC,
            "reason:field_length"));
  }

  @Test
  void hasCapacityForEnqueueReflectsQueueSaturationAndCountsPreQueueOverflow() {
    final BackendApi mockEvp = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any(), anyBoolean())).thenReturn(mockEvp);
    final int capacity = 2;
    final FlagEvaluationWriterImpl writer =
        new FlagEvaluationWriterImpl(
            capacity, Long.MAX_VALUE, TimeUnit.NANOSECONDS, factory, cfg());

    assertTrue(writer.hasCapacityForEnqueue());

    // Saturate the hand-off queue without starting the worker so no drain can free slots.
    for (int i = 0; i < capacity; i++) {
      writer.enqueue(simpleEvent("cap-flag-" + i, "on"));
    }
    assertFalse(writer.hasCapacityForEnqueue());

    // Simulate a pre-queue overflow account by the hook and surface it on flush.
    writer.countPreQueueOverflow();
    writer.flushForTest();

    final Collection<? extends MetricCollector.Metric> metrics =
        CoreMetricCollector.getInstance().drain();
    assertEquals(
        1,
        metricSum(
            metrics,
            FlagEvaluationWriterImpl.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationWriterImpl.DROP_REASON_QUEUE_OVERFLOW));

    writer.close();
  }

  private void assertHashedTargetingKeyAndOmittedContext(final FlagEvalEvent piiEvent)
      throws Exception {
    final BackendApi mockEvp = mock(BackendApi.class);
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(mockEvp);
    setup.handler.add(piiEvent);

    final FlagEvaluationTestSupport.CapturedJson captured = flushAndCapture(setup);

    final Map<String, Object> ev = eventForFlag(captured.parsed, "pii-flag");
    assertNotNull(ev);
    assertEquals(HASHED_JANE_DOE, ev.get("targeting_key"));
    assertFalse(ev.containsKey("context"));
    // The raw wire bytes must carry the hashed key and never leak the raw PII value or a per-event
    // evaluation context (the batch envelope owns the top-level "context" key, so guard on the
    // nested "evaluation" field instead).
    assertTrue(captured.raw.contains(HASHED_JANE_DOE));
    assertFalse(captured.raw.contains("jane.doe@datadoghq.com"));
    assertFalse(captured.raw.contains("\"evaluation\":"));
  }

  private static FlagEvalEvent piiEvent(final boolean observeFullEvaluationData) {
    return event(
        "pii-flag",
        "on",
        "alloc1",
        "jane.doe@datadoghq.com",
        1000L,
        observeFullEvaluationData,
        piiAttrs());
  }

  private static FlagEvalEvent piiEventDefaultConsent() {
    return event("pii-flag", "on", "alloc1", "jane.doe@datadoghq.com", 1000L, piiAttrs());
  }

  private static Map<String, Object> piiAttrs() {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("region", "us-east-1");
    return attrs;
  }

  private static void dispatchObserveFullEvaluationData(final boolean value) {
    FeatureFlaggingGateway.dispatch(
        new ServerConfiguration(
            "2024-04-17T19:40:53.716Z", "SERVER", value, null, java.util.Collections.emptyMap()));
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
