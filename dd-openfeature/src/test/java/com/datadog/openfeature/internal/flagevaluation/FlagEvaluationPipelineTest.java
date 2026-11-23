package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.buildTestWriter;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.context;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.event;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.eventForFlag;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.flushAndCapture;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.readJson;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.repeat;
import static com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.simpleEvent;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.CapturingTransport;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationTestSupport.RecordingMetrics;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

class FlagEvaluationPipelineTest {

  private final CapturingTransport transport = new CapturingTransport();
  private final RecordingMetrics metrics = new RecordingMetrics();

  private FlagEvaluationPipeline writer(final long flushInterval, final TimeUnit unit) {
    return new FlagEvaluationPipeline(
        flushInterval, unit, this.transport, context(), new RuntimeServices(this.metrics));
  }

  @Test
  void degradedCapOverflowTelemetryIsEmittedOnFlush() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();

    setup.aggregator.droppedDegradedOverflow.addAndGet(3);
    setup.handler.flush();

    assertEquals(
        3,
        setup.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_DEGRADED_CAP));
  }

  @Test
  void reportsADeliveredFlushAndTheShutdownRequest() {
    CapturingServiceProvider.reset();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.DEBUG);
    try {
      final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
      setup.add(simpleEvent("logged-flag", "on"));
      flushAndCapture(setup);
      writer(Long.MAX_VALUE, TimeUnit.NANOSECONDS).requestShutdown();

      final List<String> messages =
          CapturingServiceProvider.events().stream()
              .map(e -> e.message)
              .collect(Collectors.toList());
      assertTrue(
          messages.contains("Feature Flags flag evaluation flush delivered: payloads=1"),
          messages.toString());
      assertTrue(
          messages.contains("Feature Flags flag evaluation shutdown requested"),
          messages.toString());
    } finally {
      CapturingServiceProvider.reset();
    }
  }

  @Test
  void signalsTheWorkerWhenTheShutdownLogFails() {
    CapturingServiceProvider.reset();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.DEBUG);
    CapturingServiceProvider.failOn("flag evaluation shutdown requested");
    final FlagEvaluationPipeline writer = writer(10L, TimeUnit.SECONDS);
    writer.start();
    try {
      assertThrows(IllegalStateException.class, writer::requestShutdown);

      writer.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
      assertFalse(writer.isSerializerThreadAlive(), "The worker was not signaled");
    } finally {
      CapturingServiceProvider.reset();
    }
  }

  @Test
  void recordAfterCloseIsDroppedAndCounted() {
    final FlagEvaluationPipeline writer = writer(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    writer.close();
    simpleEvent("closed-flag", "on").recordInto(writer);
    writer.flushForTest();

    assertEquals(
        1,
        this.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_CLOSED));
    assertTrue(this.transport.posts.isEmpty());
  }

  @Test
  void closeCountsEvaluationsTheWorkerNeverFlushed() {
    final FlagEvaluationPipeline writer = writer(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    // The worker is never started, so nothing flushes these; close() must account for them rather
    // than leave them silently stranded. Stands in for the narrow window where a lock-free
    // producer records after the worker's final drain.
    simpleEvent("residual-flag-1", "on").recordInto(writer);
    simpleEvent("residual-flag-1", "on").recordInto(writer);
    simpleEvent("residual-flag-2", "on").recordInto(writer);
    writer.close();

    assertEquals(
        3,
        this.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_CLOSED));
    writer.flushForTest();
    assertTrue(this.transport.posts.isEmpty(), "the residual evaluations were drained");
  }

  @Test
  void recordAggregatesOnTheEvaluatingThread() throws Exception {
    final FlagEvaluationPipeline writer = writer(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

    simpleEvent("caller-flag", "on").recordInto(writer);
    simpleEvent("caller-flag", "on").recordInto(writer);
    writer.flushForTest();

    assertEquals(1, this.transport.posts.size());
    final Map<String, Object> ev =
        eventForFlag(readJson(this.transport.posts.get(0).body).parsed, "caller-flag");
    assertNotNull(ev);
    assertEquals(2.0, ((Number) ev.get("evaluation_count")).doubleValue());
  }

  @Test
  void recordNeverThrowsIntoTheEvaluation() {
    final FlagEvaluationPipeline writer = writer(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    final Object unhashable =
        new Object() {
          @Override
          public int hashCode() {
            throw new IllegalStateException("boom");
          }
        };

    writer.record(
        "bad-flag", "on", "alloc1", "user-1", null, 1L, true, singletonMap("k", unhashable), null);
    writer.flushForTest();

    assertTrue(this.transport.posts.isEmpty());
  }

  @Test
  void flushWithNothingRecordedPostsNothing() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();

    setup.handler.flush();
    setup.add(simpleEvent("once-flag", "on"));
    setup.handler.flush();
    setup.handler.flush();

    assertEquals(1, setup.transport.posts.size());
  }

  @Test
  void flushSweepsBucketsIdleForAWholeWindow() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();

    setup.add(simpleEvent("idle-flag", "on"));
    setup.handler.flush();
    assertEquals(1, setup.aggregator.tallyCount(), "kept through the window it was touched in");
    setup.handler.flush();
    assertEquals(0, setup.aggregator.tallyCount());
  }

  @Test
  void anUnrelatedInterruptStillFlushesWithoutTheFlagAndRestoresIt() throws Exception {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
    final boolean[] interruptedDuringPost = {true};
    setup.transport.onPost =
        () -> interruptedDuringPost[0] = Thread.currentThread().isInterrupted();
    final boolean[] interruptedAfter = {false};
    final Thread worker =
        new Thread(() -> {
          setup.handler.run();
          interruptedAfter[0] = Thread.currentThread().isInterrupted();
        });
    setup.add(simpleEvent("interrupted-flag", "on"));

    worker.start();
    worker.interrupt();
    worker.join(TimeUnit.SECONDS.toMillis(5));

    assertFalse(worker.isAlive());
    assertEquals(1, setup.transport.posts.size());
    assertFalse(interruptedDuringPost[0], "the final flush must not run interrupted");
    assertTrue(interruptedAfter[0], "the interrupt is restored");
  }

  @Test
  void degradedRowsAreSerializedWithoutTargetingKeyOrContextAndCounted() throws Exception {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
    for (int i = 0; i < ConcurrentFlagAggregator.PER_FLAG_CAP; i++) {
      setup.add(event("hot-flag", "on", "alloc1", "user-" + i, 1000L, emptyMap()));
    }
    // Over the per-flag cap: the next subjects spill into one degraded row.
    setup.add(event("hot-flag", "on", "alloc1", "late-1", 1000L, true, piiAttrs()));
    setup.add(event("hot-flag", "on", "alloc1", "late-2", 1000L, true, piiAttrs()));

    final FlagEvaluationTestSupport.CapturedJson captured = flushAndCapture(setup);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> rows =
        (List<Map<String, Object>>) (List<?>) captured.parsed.get("flagEvaluations");
    final List<Map<String, Object>> degraded =
        rows.stream().filter(r -> !r.containsKey("targeting_key")).collect(Collectors.toList());
    assertEquals(1, degraded.size());
    assertEquals(2.0, ((Number) degraded.get(0).get("evaluation_count")).doubleValue());
    assertNull(degraded.get(0).get("context"));
    assertFalse(captured.raw.contains("late-1"));
    assertFalse(captured.raw.contains("us-east-1"));
    assertEquals(
        2,
        setup.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DEGRADED_METRIC,
            "reason:" + FlagEvaluationPipeline.DEGRADED_REASON_CARDINALITY_CAP));
  }

  @Test
  void payloadLimitDropsAreCountedOnFlush() {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter(128);
    setup.add(event(repeat('f', 512), "on", "alloc1", "user-1", 1000L, emptyMap()));

    setup.handler.flush();

    assertEquals(
        1,
        setup.metrics.sum(
            FlagEvaluationPipeline.FLAG_EVALUATION_DROPPED_METRIC,
            "reason:" + FlagEvaluationPipeline.DROP_REASON_PAYLOAD_LIMIT));
  }

  @Test
  void finalFlushRunsWithoutTheInterruptFlagSet() throws Exception {
    // The final flush does socket I/O, which fails fast on an interrupted thread, so the flag must
    // be clear by the time the transport is called.
    final CountDownLatch posted = new CountDownLatch(1);
    final boolean[] interruptedDuringPost = {true};
    this.transport.onPost =
        () -> {
          interruptedDuringPost[0] = Thread.currentThread().isInterrupted();
          posted.countDown();
        };
    final FlagEvaluationPipeline writer = writer(TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);

    writer.start();
    simpleEvent("interrupt-flag", "on").recordInto(writer);
    writer.close();

    assertTrue(posted.await(5, TimeUnit.SECONDS));
    assertFalse(
        interruptedDuringPost[0],
        "final flush must not run on an interrupted thread; the drained rows would be lost");
  }

  @Test
  void closeFinalFlushesRecordedEvaluations() throws Exception {
    final CountDownLatch posted = new CountDownLatch(1);
    this.transport.onPost = posted::countDown;
    final FlagEvaluationPipeline writer = writer(TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);

    writer.start();
    simpleEvent("shutdown-flag", "on").recordInto(writer);
    writer.close();

    assertTrue(posted.await(5, TimeUnit.SECONDS));
    final Map<String, Object> json = readJson(this.transport.posts.get(0).body).parsed;
    assertNotNull(eventForFlag(json, "shutdown-flag"));
  }

  @Test
  void continuousTrafficFlushesWithoutWaitingForIdle() throws Exception {
    final FlagEvaluationPipeline writer = writer(1, TimeUnit.MILLISECONDS);

    writer.start();
    boolean posted = false;
    try {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() < deadline) {
        simpleEvent("busy-flag", "on").recordInto(writer);
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

    setup.add(event("flag-f", "on", "alloc1", "user-1", 1000L, emptyMap()));
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
      setup.add(event("split-failure-" + i, "on", "alloc1", "user-" + i, 1000L, attrs));
    }

    setup.handler.flush();
    assertEquals(2, setup.transport.posts.size());
    setup.handler.flush();
    assertEquals(2, setup.transport.posts.size());
  }

  private static final String HASHED_JANE_DOE =
      "sha256_b4698f9b6d186781fa8dc59e533578fa2d8379a46b1cf6db85cda6aa9c99e51b";

  @Test
  void observeFullEvaluationDataTrueEmitsRawTargetingKeyAndContext() throws Exception {
    // Consent travels with the evaluation (snapshotted by the hook at evaluation time); the writer
    // honours it verbatim.
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
    setup.add(piiEvent(true));

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
  void consentOffPreservesErrorCodeSignalAndNeverLeaksPiiInErrorMessage() throws Exception {
    // Upstream contract: the hook substitutes the ErrorCode name for the raw exception message
    // under consent-off (see
    // FlagEvalLoggingHookTest#errorMessageReplacedByErrorCodeUnderConsentOff).
    // This wire-level guard pins that a properly-formed consent-off event (a) still surfaces the
    // stable ErrorCode signal for operators and (b) never lets a PII-shaped string escape onto the
    // wire. Mirrors the existing PII guards on the targeting_key axis.
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
    setup.add(
        new RecordedEvaluation(
            "err-flag",
            null,
            "alloc1",
            "jane.doe@datadoghq.com",
            "TYPE_MISMATCH",
            1000L,
            false,
            emptyMap(),
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

    setup.add(simpleEvent("failed-flag", "on"));
    setup.handler.flush();
    assertEquals(1, setup.transport.posts.size());

    // The drained row must not survive the failed flush. A follow-up healthy one flushes cleanly.
    setup.add(simpleEvent("healthy-flag", "on"));
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
    setup.add(event("nan-flag", "on", "alloc1", "user-1", 1000L, true, attrs));

    final FlagEvaluationTestSupport.CapturedJson captured = flushAndCapture(setup);

    assertTrue(captured.raw.contains("\"bad-number\":\"NaN\""));
  }

  @Test
  void countContextTruncatedAccumulatesPerReason() {
    final FlagEvaluationPipeline writer = writer(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

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

  private void assertHashedTargetingKeyAndOmittedContext(final RecordedEvaluation piiEvent)
      throws Exception {
    final FlagEvaluationTestSupport.TestWriterSetup setup = buildTestWriter();
    setup.add(piiEvent);

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

  private static RecordedEvaluation piiEvent(final boolean observeFullEvaluationData) {
    return event(
        "pii-flag",
        "on",
        "alloc1",
        "jane.doe@datadoghq.com",
        1000L,
        observeFullEvaluationData,
        piiAttrs());
  }

  private static Map<String, Object> piiAttrs() {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("region", "us-east-1");
    return attrs;
  }
}
