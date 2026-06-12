package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Recorder hook that the test-only ByteBuddy advice ({@link ContinuationAdvice}, {@link
 * PendingTraceAdvice}) funnels scope-continuation lifecycle events into. It replaces the former
 * production {@code ContinuationDiagnostics} seam: the advice is woven into {@code
 * datadog.trace.core.scopemanager.ScopeContinuation} and {@code datadog.trace.core.PendingTrace} at
 * test time only, so production tracer code carries no diagnostic footprint at all.
 *
 * <p>Inlined advice runs in the same app classloader as this class at test time, so it can call
 * these statics directly. Every entry point first checks the {@link #recording} flag and is fully
 * wrapped so a diagnostic failure can never propagate back into the tracer.
 */
public final class ScopeContinuationProbe {
  /**
   * Mirrors {@code ScopeContinuation.CANCELLED} (see {@code
   * dd-trace-core/.../scopemanager/ScopeContinuation.java}). A continuation is resolved exactly
   * when its {@code count} field transitions to this sentinel during a cancel call. Kept in sync by
   * {@code ScopeContinuationProbeTest}.
   */
  static final int CANCELLED = Integer.MIN_VALUE >> 1;

  private static volatile boolean recording = false;
  private static volatile boolean installed = false;

  /** Cached reflective handle to the package-private {@code ScopeContinuation.source} field. */
  private static volatile Field sourceField;

  /**
   * Continuations already recorded as resolved, by identity. The clean-resolution path that flips
   * {@code count} to {@link #CANCELLED} can be observed by two inlined advice frames (the {@code
   * cancelFromContinuedScopeClose} slow path delegates to {@code cancel()}); since {@code
   * CANCELLED} is terminal, dedup by identity so each continuation resolves exactly once — matching
   * the single {@code notifyResolve} the old production seam emitted.
   */
  private static final Set<Object> resolved =
      Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

  private ScopeContinuationProbe() {}

  /** Installs the transformer (once per JVM) and starts recording. */
  static synchronized void enable() {
    if (!installed) {
      ScopeContinuationTransformer.install();
      installed = true;
    }
    recording = true;
  }

  /** Stops recording. The transformer stays installed (inert while not recording). */
  static void disable() {
    recording = false;
  }

  /** Clears per-window state. Called from {@link ScopeDiagnostics#reset()}. */
  static void reset() {
    resolved.clear();
  }

  // ---- advice entry points (public so inlined advice can reference them) -------------------

  /** {@code ScopeContinuation.register()} exit: the continuation was captured. */
  public static void onCapture(Object self) {
    if (!recording) {
      return;
    }
    try {
      AgentScope.Continuation continuation = (AgentScope.Continuation) self;
      AgentSpan span = continuation.span();
      if (span != null) {
        ScopeDiagnostics.recordCapture(
            continuation, span.getTraceId(), span.getSpanId(), sourceOf(self));
      }
    } catch (Throwable ignored) {
      // diagnostics must never disturb the tracer
    }
  }

  /**
   * {@code ScopeContinuation.activate()} exit: a real activation happened. The rollback branch
   * returns the {@link AgentTracer#noopScope() noop scope} singleton, so a returned noop scope is
   * skipped — this exactly reproduces the original "success branch only" semantics.
   */
  public static void onActivate(Object self, Object returnedScope) {
    if (!recording) {
      return;
    }
    if (returnedScope == AgentTracer.noopScope()) {
      return;
    }
    try {
      AgentScope.Continuation continuation = (AgentScope.Continuation) self;
      AgentSpan span = continuation.span();
      if (span != null) {
        ScopeDiagnostics.recordActivate(
            continuation, span.getTraceId(), span.getSpanId(), sourceOf(self));
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * {@code ScopeContinuation.cancel()} / {@code cancelFromContinuedScopeClose()} exit. The original
   * production seam fired only from inside the clean resolution branch; here we detect that branch
   * by observing the {@code count} field transition to {@link #CANCELLED} during this call. A
   * cancel with outstanding activations leaves {@code count} unchanged (not a resolution).
   */
  public static void onResolve(Object self, int countBefore, int countAfter) {
    if (!recording) {
      return;
    }
    if (countBefore == CANCELLED || countAfter != CANCELLED) {
      return;
    }
    try {
      if (resolved.add(self)) {
        ScopeDiagnostics.recordResolve((AgentScope.Continuation) self, false);
      }
    } catch (Throwable ignored) {
    }
  }

  /** {@code PendingTrace.write()} root-written site. */
  public static void onRootWritten(Object traceId) {
    if (!recording) {
      return;
    }
    try {
      ScopeDiagnostics.recordRootWritten((DDTraceId) traceId);
    } catch (Throwable ignored) {
    }
  }

  /**
   * Reads the package-private {@code source} byte field, falling back to the {@code -1} sentinel.
   */
  private static byte sourceOf(Object self) {
    try {
      Field field = sourceField;
      if (field == null) {
        field = self.getClass().getDeclaredField("source");
        field.setAccessible(true);
        sourceField = field;
      }
      return field.getByte(self);
    } catch (Throwable ignored) {
      return (byte) -1;
    }
  }
}
