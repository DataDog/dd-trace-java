package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
    AgentScope active = tracer.activateSpan(span);
    // Same-span reuse resolves the continuation before resume() returns.
    ContextContinuation continuation = tracer.capture(span);
    ContextScope reused = continuation.resume();
    reused.close();
    active.close();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(1, report.records().size());
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
    assertEquals(Long.valueOf(report.records().get(0).seq), linked.continuationSeq);
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
