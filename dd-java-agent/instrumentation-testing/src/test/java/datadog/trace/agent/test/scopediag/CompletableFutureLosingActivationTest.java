package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.NoopScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

/** Reconstructs the original advice interleaving using a real JDK completion and continuation. */
@EnabledOnJre(JRE.JAVA_8)
class CompletableFutureLosingActivationTest {
  @Test
  void directResumeAfterReleaseStillFails() throws Exception {
    assertLosingActivation(false, 6);
  }

  @Test
  void speculativeResumeIsRecordedWithoutFailing() throws Exception {
    assertLosingActivation(true, 6);
  }

  @Test
  void speculativeResumeIsRecognizedWithCallsitesDisabled() throws Exception {
    assertLosingActivation(true, 0);
  }

  private void assertLosingActivation(boolean speculative, int frames) throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = CoreTracer.builder().writer(writer).strictTraceWrites(true).build();
    try {
      ScopeDiagnostics.startRecording(frames);
      AgentSpan parent = tracer.startSpan("test", "parent");
      ContextContinuation continuation = tracer.capture(parent).hold();
      AtomicInteger callbacks = new AtomicInteger();
      CompletableFuture<Integer> source = new CompletableFuture<>();
      CompletableFuture<Integer> result =
          source.thenApply(
              value -> {
                assertSame(parent, tracer.activeSpan());
                callbacks.incrementAndGet();
                AgentSpan child = tracer.startSpan("test", "child");
                assertEquals(parent.getSpanId(), ((DDSpan) child).getParentId());
                child.finish();
                return value + 1;
              });

      Field stack = CompletableFuture.class.getDeclaredField("stack");
      stack.setAccessible(true);
      Object completion = stack.get(source);
      assertNotNull(completion);
      Method tryFire = completion.getClass().getDeclaredMethod("tryFire", int.class);
      tryFire.setAccessible(true);

      // The losing advice has read the continuation, then pauses before resume().
      ContextContinuation observedByLoser = continuation;
      FutureTask<Void> winner =
          new FutureTask<>(
              () -> {
                try (ContextScope ignored = continuation.resume()) {
                  assertTrue(source.complete(41));
                  continuation.release();
                }
                return null;
              });
      new Thread(winner, "completion-winner").start();
      winner.get(10, TimeUnit.SECONDS);
      assertFalse(ScopeDiagnostics.report().hasProblems());

      // Reconstruct the losing advice followed by the actual retired JDK tryFire body.
      try (ContextScope scope =
          speculative ? resumeObservedContinuation(observedByLoser) : observedByLoser.resume()) {
        assertSame(NoopScope.INSTANCE, scope);
        assertNull(tryFire.invoke(completion, 0));
      }
      assertEquals(42, result.get(10, TimeUnit.SECONDS).intValue());
      assertEquals(1, callbacks.get());
      assertNull(tracer.activeSpan());
      parent.finish();

      assertEquals(1, writer.size(), "strict trace completed without delayed-write fallback");
      assertEquals(2, writer.get(0).size());
      ScopeDiagnosticsReport report = ScopeDiagnostics.report();
      assertEquals(0, report.leakCount());
      assertEquals(0, report.doubleCount());
      assertEquals(speculative ? 0 : 1, report.activateAfterResolveCount());
      assertEquals(!speculative, report.hasProblems());
      assertEquals(1, report.records().get(0).failedActivations().size());
      assertEquals(
          speculative ? ScopeEvent.Type.ACTIVATE_REJECTED : ScopeEvent.Type.ACTIVATE_FAILED,
          report.records().get(0).failedActivations().get(0).type);
      assertTrue(
          report.renderTimeline().contains(speculative ? "act-reject (speculative)" : "act-fail"));
    } finally {
      ScopeDiagnostics.stop();
      ScopeDiagnostics.reset();
      tracer.close();
    }
  }

  private static ContextScope resumeObservedContinuation(ContextContinuation observed)
      throws Exception {
    // Seed the previously observed reference to reproduce the read-before-cleanup interleaving
    // deterministically. This exercises the real helper/probe path, not a fully instrumented race.
    ConcurrentState state = ConcurrentState.FACTORY.create();
    Field continuation = ConcurrentState.class.getDeclaredField("continuation");
    continuation.setAccessible(true);
    continuation.set(state, observed);
    Method activate = ConcurrentState.class.getDeclaredMethod("activateAndContinueContinuation");
    activate.setAccessible(true);
    return (ContextScope) activate.invoke(state);
  }
}
