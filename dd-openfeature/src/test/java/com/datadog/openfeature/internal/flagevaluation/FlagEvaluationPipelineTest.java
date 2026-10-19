package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.buildTestWriter;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.context;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.event;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.eventForFlag;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.flushAndCapture;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.flushAndCaptureJson;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.readJson;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.repeat;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.simpleEvent;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.EventQueue;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.CapturingTransport;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.RecordingMetrics;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FlagEvaluationPipelineTest {

  private final CapturingTransport transport = new CapturingTransport();
  private final RecordingMetrics metrics = new RecordingMetrics();

  private FlagEvaluationPipeline writer(
      final int capacity, final long flushInterval, final TimeUnit unit) {
    return new FlagEvaluationPipeline(
        capacity,
        flushInterval,
        unit,
        this.transport,
        context(),
        new RuntimeServices(this.metrics));
  }

  @Test
  void degradedCapOverflowTelemetryIsEmittedOnFlush() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();

    setup.handler.addDroppedDegradedOverflowForTest(3);
    setup.handler.flush();

    assertEquals(
        3,
        setup.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_DEGRADED_CAP));
  }

  @Test
  void queueOverflowIncrementsObservableDropCounter() {
    final FlagEvaluationPipeline writer = writer(2, 10L, TimeUnit.SECONDS);

    for (int i = 0; i < 100; i++) {
      writer.enqueue(simpleEvent("of-flag", "on"));
    }

    assertTrue(writer.droppedQueueOverflow() > 0);
    final long queueDrops = writer.droppedQueueOverflow();
    writer.flushForTest();
    assertEquals(
        queueDrops,
        this.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_QUEUE_OVERFLOW));
  }

  @Test
  void enqueueAfterCloseIsDroppedAndCounted() {
    final FlagEvaluationPipeline writer = writer(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    writer.close();
    writer.enqueue(simpleEvent("closed-flag", "on"));

    assertEquals(
        1,
        this.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_CLOSED));
    assertNull(writer.pollQueuedEventForTest());
  }

  @Test
  void closeSweepsAndCountsEventsLeftInTheQueue() {
    final FlagEvaluationPipeline writer = writer(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    // The worker is never started, so nothing drains these; close() must account for them rather
    // than leave them silently stranded. Stands in for the narrow window where a lock-free
    // producer offers after the worker's final drain.
    writer.enqueue(simpleEvent("residual-flag-1", "on"));
    writer.enqueue(simpleEvent("residual-flag-2", "on"));
    writer.close();

    assertEquals(
        2,
        this.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_CLOSED));
    assertNull(writer.pollQueuedEventForTest());
  }

  @Test
  void enqueueIgnoresNullEvent() {
    final FlagEvaluationPipeline writer = writer(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    writer.enqueue(null);

    assertNull(writer.pollQueuedEventForTest());
    assertEquals(0, writer.droppedQueueOverflow());
  }

  @Test
  void enqueueDoesNotAggregateOnTheCallingThread() {
    final FlagEvaluationPipeline writer = writer(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    writer.enqueue(simpleEvent("g2-flag", "on"));
    writer.enqueue(simpleEvent("g2-flag", "on"));

    assertEquals(0, writer.aggregatorFullTierSizeForTest());
    assertEquals(0, writer.droppedQueueOverflow());
  }

  @Test
  void flushIfNecessarySkipsEmptyStateAndWaitsForInterval() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();

    setup.handler.flushIfNecessary();
    setup.handler.add(simpleEvent("pending-flag", "on"));
    setup.handler.drainAndAggregate();
    setup.handler.flushIfNecessary();

    assertEquals(1, setup.handler.fullTierSizeForTest());
  }

  @Test
  void flushIfNecessaryDoesNotReturnEarlyWhenOnlyQueueDropsArePending() {
    final AtomicLong queueDrops = new AtomicLong(1);
    final FlagEvaluationPipeline.FlagEvaluationSerializingHandler handler =
        new FlagEvaluationPipeline.FlagEvaluationSerializingHandler(
            this.transport,
            new EventQueue<>(16),
            Long.MAX_VALUE,
            TimeUnit.NANOSECONDS,
            context(),
            queueDrops,
            new ConcurrentHashMap<>(),
            FlagEvaluationPipeline.FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES,
            new RuntimeServices(this.metrics));

    handler.flushIfNecessary();

    assertEquals(1, queueDrops.get());
  }

  @Test
  void degradedBucketsAreSerializedWithoutTargetingKeyOrContext() throws Exception {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
    setup.handler.addDegradedBucketForTest("degraded-flag", "on", "alloc1", null, 1000L);

    final Map<String, Object> json = flushAndCaptureJson(setup);

    final Map<String, Object> ev = eventForFlag(json, "degraded-flag");
    assertNotNull(ev);
    assertNull(ev.get("targeting_key"));
    assertNull(ev.get("context"));
  }

  @Test
  void testHandlerCanSimulateAndClearDegradedTierAtCap() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();

    setup.handler.simulateDegradedTierAtCap();
    setup.handler.clearAggregationForTest();
    setup.handler.add(simpleEvent("after-clear", "on"));
    setup.handler.drainAndAggregate();

    assertEquals(1, setup.handler.fullTierSizeForTest());
  }

  @Test
  void payloadLimitDropsAreCountedOnFlush() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(128);
    setup.handler.add(event(repeat('f', 512), "on", "alloc1", "user-1", 1000L, emptyMap()));

    setup.handler.drainAndAggregate();
    setup.handler.flush();

    assertEquals(
        1,
        setup.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_PAYLOAD_LIMIT));
  }

  @Test
  void finalFlushRunsWithoutTheInterruptFlagSet() throws Exception {
    // close() interrupts the worker to break it out of poll(). The final flush does socket I/O,
    // which fails fast on an interrupted thread, so the flag must be clear by the time the
    // transport is called.
    final CountDownLatch posted = new CountDownLatch(1);
    final boolean[] interruptedDuringPost = {true};
    this.transport.onPost =
        () -> {
          interruptedDuringPost[0] = Thread.currentThread().isInterrupted();
          posted.countDown();
        };
    final FlagEvaluationPipeline writer = writer(64, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);

    writer.start();
    writer.enqueue(simpleEvent("interrupt-flag", "on"));
    writer.close();

    assertTrue(posted.await(5, TimeUnit.SECONDS));
    assertFalse(
        interruptedDuringPost[0],
        "final flush must not run on an interrupted thread; the drained rows would be lost");
  }

  @Test
  void closeDrainsAndFinalFlushesQueuedEvents() throws Exception {
    final CountDownLatch posted = new CountDownLatch(1);
    this.transport.onPost = posted::countDown;
    final FlagEvaluationPipeline writer = writer(64, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);

    writer.start();
    writer.enqueue(simpleEvent("shutdown-flag", "on"));
    writer.close();

    assertTrue(posted.await(5, TimeUnit.SECONDS));
    final Map<String, Object> json = readJson(this.transport.posts.get(0).body).parsed;
    assertNotNull(eventForFlag(json, "shutdown-flag"));
  }

  @Test
  void continuousTrafficFlushesWithoutWaitingForIdle() throws Exception {
    final FlagEvaluationPipeline writer = writer(1 << 12, 1, TimeUnit.MILLISECONDS);

    writer.start();
    boolean posted = false;
    try {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() < deadline) {
        writer.enqueue(simpleEvent("busy-flag", "on"));
        if (!this.transport.posts.isEmpty()) {
          posted = true;
          break;
        }
      }
    } finally {
      writer.close();
    }

    assertTrue(posted);
  }

  @Test
  void flushPostsToFlagevaluationEndpoint() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();

    setup.handler.add(event("flag-f", "on", "alloc1", "user-1", 1000L, emptyMap()));
    setup.handler.drainAndAggregate();
    setup.handler.flush();

    assertEquals(1, setup.transport.posts.size());
    assertEquals("flagevaluation", setup.transport.posts.get(0).route);
  }

  @Test
  void splitPostFailureDoesNotRetryAlreadySentPayloads() {
    final int limit = 1_100;
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(limit);
    setup.transport.failingPost = index -> index == 2;

    for (int i = 0; i < 4; i++) {
      final Map<String, Object> attrs = new HashMap<>();
      attrs.put("payload", repeat('x', 180));
      setup.handler.add(event("split-failure-" + i, "on", "alloc1", "user-" + i, 1000L, attrs));
    }

    setup.handler.drainAndAggregate();
    setup.handler.flush();
    assertEquals(2, setup.transport.posts.size());
    setup.handler.flush();
    assertEquals(2, setup.transport.posts.size());
  }

  private static final String HASHED_JANE_DOE =
      "sha256_b4698f9b6d186781fa8dc59e533578fa2d8379a46b1cf6db85cda6aa9c99e51b";

  @Test
  void observeFullEvaluationDataTrueEmitsRawTargetingKeyAndContext() throws Exception {
    // Consent travels on the event (snapshotted by the hook at evaluation time); the writer honours
    // it verbatim.
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
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
    // when no UFC has been received.
    assertHashedTargetingKeyAndOmittedContext(piiEventDefaultConsent());
  }

  @Test
  void consentOffPreservesErrorCodeSignalAndNeverLeaksPiiInErrorMessage() throws Exception {
    // Upstream contract: the hook substitutes the ErrorCode name for the raw exception message
    // under consent-off (see
    // FlagEvalLoggingHookTest#errorMessageReplacedByErrorCodeUnderConsentOff).
    // This wire-level guard pins that a properly-formed consent-off event (a) still surfaces the
    // stable ErrorCode signal for operators and (b) never lets a PII-shaped string escape onto the
    // wire. Mirrors the existing PII guards on the targeting_key axis.
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
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
  void deliveryFailureClearsAggregatorSoLaterFlushesRecover() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
    setup.transport.failingPost = index -> index == 1;

    setup.handler.add(simpleEvent("failed-flag", "on"));
    setup.handler.drainAndAggregate();
    setup.handler.flush();
    assertEquals(1, setup.transport.posts.size());

    // The bucket must not survive the failed flush. A follow-up healthy event flushes cleanly.
    setup.handler.add(simpleEvent("healthy-flag", "on"));
    setup.handler.drainAndAggregate();
    setup.handler.flush();
    assertEquals(2, setup.transport.posts.size());
    final Map<String, Object> json = readJson(setup.transport.posts.get(1).body).parsed;
    assertNull(eventForFlag(json, "failed-flag"));
    assertNotNull(eventForFlag(json, "healthy-flag"));
  }

  @Test
  void nonFiniteContextNumbersAreSerializedAsStrings() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("bad-number", Double.NaN);
    setup.handler.add(event("nan-flag", "on", "alloc1", "user-1", 1000L, true, attrs));

    final FlagEvaluationTestSupport.CapturedJson captured = flushAndCapture(setup);

    assertTrue(captured.raw.contains("\"bad-number\":\"NaN\""));
  }

  @Test
  void countContextTruncatedAccumulatesPerReason() {
    final FlagEvaluationPipeline writer = writer(16, Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    writer.countContextTruncated("field_count");
    writer.countContextTruncated("field_count");
    writer.countContextTruncated("field_length");
    writer.flushForTest();

    assertEquals(
        2,
        this.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_CONTEXT_TRUNCATED_METRIC, "reason:field_count"));
    assertEquals(
        1,
        this.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_CONTEXT_TRUNCATED_METRIC,
            "reason:field_length"));
  }

  @Test
  void hasCapacityForEnqueueReflectsQueueSaturationAndCountsPreQueueOverflow() {
    final int capacity = 2;
    final FlagEvaluationPipeline writer = writer(capacity, Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    assertTrue(writer.hasCapacityForEnqueue());

    // Saturate the hand-off queue without starting the worker so no drain can free slots.
    for (int i = 0; i < capacity; i++) {
      writer.enqueue(simpleEvent("cap-flag-" + i, "on"));
    }
    assertFalse(writer.hasCapacityForEnqueue());

    // Simulate a pre-queue overflow account by the hook and surface it on flush.
    writer.countPreQueueOverflow();
    writer.flushForTest();

    assertEquals(
        1,
        this.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_QUEUE_OVERFLOW));

    writer.close();
  }

  private void assertHashedTargetingKeyAndOmittedContext(final FlagEvalEvent piiEvent)
      throws Exception {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
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
}
