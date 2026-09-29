package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.PendingTrace;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises diagnostics against continuations created by a real {@link CoreTracer}. */
class ScopeDiagnosticsIntegrationTest {

  private CoreTracer tracer;

  @AfterEach
  void tearDown() {
    ScopeDiagnostics.stop();
    ScopeDiagnostics.reset();
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void capturesRealLeakAndResolvedContinuation() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation leaked = tracer.capture(span);
    ContextContinuation resolved = tracer.capture(span);
    resolved.release();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(2, report.records().size(), "both captures recorded");
    assertEquals(1, report.leakCount(), "exactly the un-resolved continuation leaks");
    assertTrue(report.hasProblems());
    assertFalse(leaked.toString().isEmpty());

    span.finish();
  }

  @Test
  void sameSpanReactivationIsNotFlaggedActivateAfterResolve() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    ContextScope active = tracer.activateSpan(span);
    // Same-span reuse resolves the continuation before resume() returns.
    ContextContinuation continuation = tracer.capture(span);
    ContextScope reused = continuation.resume();
    reused.close();
    active.close();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(1, report.records().size());
    ContinuationRecord record = report.records().get(0);
    assertEquals(1, record.resumes().size(), "same-span resume must be observed");
    assertNotNull(record.terminal());
    assertTrue(
        record.resumes().get(0).nanos <= record.terminal().nanos,
        "resume must be timestamped before its nested resolution");
    assertEquals(
        0,
        report.activateAfterResolveCount(),
        "a same-span re-activation resolved during activate() is not activate-after-resolve");
    assertEquals(0, report.leakCount());
    assertFalse(report.hasProblems());
  }

  @Test
  void resolvedContinuationDoesNotLeak() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation continuation = tracer.capture(span);
    ContextScope scope = continuation.resume();
    scope.close();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(1, report.records().size());
    assertEquals(0, report.leakCount(), "activated then closed continuation is resolved");
  }

  @Test
  void repeatedContinuedScopeCloseIsFlagged() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();
    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation continuation = tracer.capture(span);
    ContextScope scope = continuation.resume();
    scope.close();
    assertFalse(ScopeDiagnostics.report().hasProblems());
    scope.close();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(1, report.doubleCount());
    assertEquals(0, report.leakCount());
    assertTrue(report.hasProblems());
    assertTrue(report.renderTimeline().contains("DOUBLE_FINISH"));
  }

  @Test
  void heldContinuationScopeCloseAndReleaseResolveOnce() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();
    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation continuation = tracer.capture(span).hold();
    ContextScope scope = continuation.resume();
    scope.close();
    assertEquals(1, ScopeDiagnostics.report().leakCount());
    continuation.release();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(1, report.records().size());
    assertEquals(ContinuationStatus.RELEASED, report.records().get(0).status());
    assertEquals(ScopeEvent.Type.RESOLVE_RELEASE, report.records().get(0).terminal().type);
    assertTrue(report.renderTimeline().contains("RELEASED"));
    assertTrue(report.renderTimeline().contains("release "));
    assertFalse(report.renderTimeline().contains("cancel"));
    assertEquals(0, report.doubleCount());
    assertEquals(0, report.leakCount());
    assertFalse(report.hasProblems());
  }

  @Test
  void releaseWithoutResumeIsReportedAsReleased() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();
    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    tracer.capture(span).release();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(1, report.records().size());
    assertEquals(ContinuationStatus.RELEASED, report.records().get(0).status());
    assertEquals(ScopeEvent.Type.RESOLVE_RELEASE, report.records().get(0).terminal().type);
    assertFalse(report.hasProblems());
  }

  @Test
  void scopeLifetimeRecordedAndLinkedToContinuation() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation continuation = tracer.capture(span);
    ContextScope scope = continuation.resume();
    scope.close();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    ScopeRecord linked = continuationScope(report);
    assertNotNull(linked, "the resumed scope was recorded");
    assertNotNull(linked.open(), "scope open observed");
    assertTrue(linked.closed(), "scope close observed");
    assertEquals(0, report.neverClosedScopeCount());
    assertEquals(1, report.records().size());
    ContinuationRecord record = report.records().get(0);
    assertNotNull(record.capture());
    assertFalse(record.orphan);
    assertEquals(1, record.resumes().size());
    assertEquals(ContinuationStatus.FINISHED, record.status());
    assertEquals(span.getTraceId(), record.traceId);
    assertEquals(span.getSpanId(), record.spanId);
    assertEquals("op", record.spanName);
    assertEquals(record.source, linked.source);
    assertFalse(record.source == (byte) -1, "source must not fall back after reflection failure");
    assertEquals(Long.valueOf(record.seq), linked.continuationSeq);
  }

  @Test
  void swappedContextDoesNotCreateCloseOwnedScope() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    Context previous = tracer.swap(span);
    previous.swap();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(0, report.neverClosedScopeCount());
    assertTrue(report.scopeRecords().isEmpty(), "stack swaps do not own scope closure");
  }

  @Test
  void rootIterationScopeDelegatesCleanup() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "iteration");
    tracer.activateNext(span);

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(1, report.deferredCleanupScopeCount());
    assertEquals(0, report.neverClosedScopeCount());
    assertFalse(report.hasProblems());

    tracer.closePrevious(true);
  }

  @Test
  void waitsForAsynchronousScopeCleanup() throws Exception {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation continuation = tracer.capture(span);
    CountDownLatch scopeOpened = new CountDownLatch(1);
    CountDownLatch closeScope = new CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              try (ContextScope ignored = continuation.resume()) {
                scopeOpened.countDown();
                closeScope.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    worker.start();

    assertTrue(scopeOpened.await(5, TimeUnit.SECONDS));
    assertTrue(ScopeDiagnostics.report().hasIncompleteLifecycles());
    closeScope.countDown();
    ScopeDiagnostics.awaitQuiescence();
    worker.join();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertFalse(report.hasIncompleteLifecycles());
    assertFalse(report.hasProblems());
    span.finish();
  }

  @Test
  void neverClosedScopeIsFlagged() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation continuation = tracer.capture(span);
    ContextScope scope = continuation.resume();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(1, report.neverClosedScopeCount(), "the open scope never closed");
    assertEquals(1, report.leakCount(), "and the continuation it backs also leaks");
    assertTrue(report.hasProblems());

    scope.close();
    span.finish();
  }

  @Test
  void eventsAfterStopAreRejected() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();
    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation continuation = tracer.capture(span);

    ScopeDiagnostics.stop();
    continuation.release();
    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(1, report.leakCount(), "the resolution happened outside the recording window");
    span.finish();
  }

  @Test
  void resumeAfterRootWriteIsRecorded() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();
    ScopeDiagnostics.startRecording();
    AgentSpan span = tracer.startSpan("test", "op");
    ContextContinuation continuation = tracer.capture(span);
    try {
      span.finish();
      ((PendingTrace) ((DDSpan) span).spanContext().getTraceCollector()).write();
      assertEquals(0, ScopeDiagnostics.report().lateCount());
      try (ContextScope ignored = continuation.resume()) {
        assertEquals(1, ScopeDiagnostics.report().lateCount());
      }
      assertEquals(0, ScopeDiagnostics.report().leakCount());
    } finally {
      continuation.release();
    }
  }

  @Test
  void resumeAfterReleaseRecordsFailedActivation() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();
    ScopeDiagnostics.startRecording();
    AgentSpan span = tracer.startSpan("test", "op");
    try {
      ContextContinuation continuation = tracer.capture(span);
      continuation.release();
      assertEquals(0, ScopeDiagnostics.report().activateAfterResolveCount());
      continuation.resume().close();
      ScopeDiagnosticsReport report = ScopeDiagnostics.report();
      assertEquals(1, report.records().size());
      assertEquals(1, report.records().get(0).failedActivations().size());
      assertTrue(report.records().get(0).resumes().isEmpty());
      assertEquals(1, report.activateAfterResolveCount());
      assertEquals(0, report.leakCount());
    } finally {
      span.finish();
    }
  }

  @Test
  void outOfOrderScopeCloseIsRecorded() throws Exception {
    assertMisplacedClose(false);
  }

  @Test
  void wrongThreadScopeCloseIsRecorded() throws Exception {
    assertMisplacedClose(true);
  }

  private void assertMisplacedClose(boolean anotherThread) throws Exception {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();
    ScopeDiagnostics.startRecording();
    AgentSpan outerSpan = tracer.startSpan("test", "outer");
    AgentSpan innerSpan = tracer.startSpan("test", "inner");
    ContextScope outer = tracer.activateSpan(outerSpan);
    ContextScope inner = tracer.activateSpan(innerSpan);
    try {
      assertEquals(0, ScopeDiagnostics.report().closeWrongThreadCount());
      String closingThread = Thread.currentThread().getName();
      if (anotherThread) {
        FutureTask<Void> close = new FutureTask<>(outer::close, null);
        Thread worker = new Thread(close, "scope-diagnostics-wrong-thread");
        worker.setDaemon(true);
        worker.start();
        close.get(5, TimeUnit.SECONDS);
        closingThread = worker.getName();
      } else {
        outer.close();
      }
      ScopeDiagnosticsReport report = ScopeDiagnostics.report();
      assertEquals(1, report.closeWrongThreadCount());
      ScopeRecord record =
          report.scopeRecords().stream()
              .filter(scope -> scope.spanId == outerSpan.getSpanId())
              .findFirst()
              .orElseThrow(() -> new AssertionError("outer scope was not recorded"));
      assertEquals(1, record.wrongThreadCloses().size());
      assertEquals(closingThread, record.wrongThreadCloses().get(0).threadName);
      assertFalse(record.closed(), "owner stack has not unwound yet");
    } finally {
      inner.close();
      innerSpan.finish();
      outerSpan.finish();
    }
    assertEquals(0, ScopeDiagnostics.report().neverClosedScopeCount());
    assertEquals(1, ScopeDiagnostics.report().closeWrongThreadCount());
  }

  private static ScopeRecord continuationScope(ScopeDiagnosticsReport report) {
    List<ScopeRecord> scopes = report.scopeRecords();
    for (ScopeRecord s : scopes) {
      if (s.continuationSeq != null) {
        return s;
      }
    }
    return null;
  }
}
