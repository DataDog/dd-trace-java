package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

  // cached reflective handles for scope-lifecycle reads (set-once, best-effort)
  private static volatile Field scopeSourceField; // ContinuableScope.source
  private static volatile Field continuationField; // ContinuingScope.continuation
  private static volatile Field scopeManagerField; // ContinuableScope.scopeManager
  private static volatile Method scopeStackMethod; // ContinuableScopeManager.scopeStack()
  private static volatile Method checkTopMethod; // ScopeStack.checkTop(ContinuableScope)

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
            continuation, span.getTraceId(), span.getSpanId(), spanName(span), sourceOf(self));
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
  public static void onActivate(Object self, Object returnedScope, long activateNanos) {
    if (!recording) {
      return;
    }
    try {
      AgentScope.Continuation continuation = (AgentScope.Continuation) self;
      if (returnedScope == AgentTracer.noopScope()) {
        // activate() returned the noop scope: the continuation was already resolved. This is the
        // activate-after-resolve signal — the engine records it only if a terminal was seen.
        ScopeDiagnostics.recordActivateFailed(continuation);
        return;
      }
      AgentSpan span = continuation.span();
      if (span != null) {
        ScopeDiagnostics.recordActivate(
            continuation,
            span.getTraceId(),
            span.getSpanId(),
            spanName(span),
            sourceOf(self),
            activateNanos);
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
  public static void onResolve(
      Object self, String method, int countBefore, int countAfter, long resolveNanos) {
    if (!recording) {
      return;
    }
    if (countAfter != CANCELLED) {
      return; // not a resolution
    }
    // An explicit cancel() is a discard; cancelFromContinuedScopeClose() is a normal finish once
    // the continued scope closes. (Caveat: the rare cancelFromContinuedScopeClose slow path
    // delegates to cancel(), so a multi-activation finish is recorded as a cancel.)
    boolean cancelled = "cancel".equals(method);
    try {
      AgentScope.Continuation continuation = (AgentScope.Continuation) self;
      if (countBefore == CANCELLED) {
        // already cancelled before this call: a genuine second finish/cancel (the slow-path
        // artifact always transitions 1->CANCELLED, never CANCELLED->CANCELLED). Surface it as a
        // double finish, bypassing the first-resolution dedup.
        ScopeDiagnostics.recordResolve(continuation, cancelled, resolveNanos);
      } else if (resolved.add(self)) {
        // first clean transition to CANCELLED; later observations of the SAME transition (the
        // cancelFromContinuedScopeClose slow path's nested cancel()) are suppressed
        ScopeDiagnostics.recordResolve(continuation, cancelled, resolveNanos);
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
   * {@code ContinuableScope.afterActivated()} exit: a scope became active. Re-activations (parent
   * restored after a child closes) reach here too; the engine keeps only the first per scope
   * identity. Links to the spawning continuation when the scope is a {@code ContinuingScope}.
   */
  public static void onScopeOpen(Object scope) {
    if (!recording) {
      return;
    }
    try {
      AgentSpan span = ((AgentScope) scope).span();
      DDTraceId traceId = span != null ? span.getTraceId() : DDTraceId.ZERO;
      long spanId = span != null ? span.getSpanId() : 0L;
      String name = span != null ? spanName(span) : null;
      ScopeDiagnostics.recordScopeOpen(
          scope, traceId, spanId, name, scopeSourceOf(scope), continuationOf(scope));
    } catch (Throwable ignored) {
    }
  }

  /**
   * {@code ContinuableScope.onProperClose()} exit: the scope was popped from its thread's stack.
   */
  public static void onScopeClose(Object scope) {
    if (!recording) {
      return;
    }
    try {
      ScopeDiagnostics.recordScopeClose(scope);
    } catch (Throwable ignored) {
    }
  }

  /**
   * {@code ContinuableScope.close()} entry: if the scope is not on top of its thread's stack, this
   * is an out-of-order / wrong-thread close. Best-effort — silently does nothing if the internal
   * stack check cannot be reached reflectively.
   */
  public static void onScopeClosing(Object scope) {
    if (!recording) {
      return;
    }
    try {
      if (isNotOnTop(scope)) {
        ScopeDiagnostics.recordScopeCloseWrongThread(scope);
      }
    } catch (Throwable ignored) {
    }
  }

  /** Snapshots the span name as a String (the CharSequence may mutate later), or {@code null}. */
  private static String spanName(AgentSpan span) {
    try {
      CharSequence name = span.getSpanName();
      return name == null ? null : name.toString();
    } catch (Throwable ignored) {
      return null;
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

  /** Reads the {@code source} byte of a scope (declared on {@code ContinuableScope}). */
  private static byte scopeSourceOf(Object scope) {
    try {
      Field field = scopeSourceField;
      if (field == null) {
        field = findField(scope.getClass(), "source");
        scopeSourceField = field;
      }
      return field != null ? field.getByte(scope) : (byte) -1;
    } catch (Throwable ignored) {
      return (byte) -1;
    }
  }

  /**
   * The continuation that spawned a scope, read from {@code ContinuingScope.continuation}; {@code
   * null} for a plain (non-continuation) scope.
   */
  private static AgentScope.Continuation continuationOf(Object scope) {
    try {
      Field field = continuationField;
      if (field == null) {
        field = findField(scope.getClass(), "continuation");
        continuationField = field;
      }
      if (field == null || !field.getDeclaringClass().isInstance(scope)) {
        return null; // not a ContinuingScope
      }
      Object value = field.get(scope);
      return value instanceof AgentScope.Continuation ? (AgentScope.Continuation) value : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** Best-effort: {@code true} when the scope is not on top of its thread's scope stack. */
  private static boolean isNotOnTop(Object scope) {
    try {
      Field managerField = scopeManagerField;
      if (managerField == null) {
        managerField = findField(scope.getClass(), "scopeManager");
        scopeManagerField = managerField;
      }
      Object manager = managerField != null ? managerField.get(scope) : null;
      if (manager == null) {
        return false;
      }
      Method stackMethod = scopeStackMethod;
      if (stackMethod == null) {
        stackMethod = findMethod(manager.getClass(), "scopeStack", 0);
        scopeStackMethod = stackMethod;
      }
      Object stack = stackMethod != null ? stackMethod.invoke(manager) : null;
      if (stack == null) {
        return false;
      }
      Method check = checkTopMethod;
      if (check == null) {
        check = findMethod(stack.getClass(), "checkTop", 1);
        checkTopMethod = check;
      }
      if (check == null) {
        return false;
      }
      Object onTop = check.invoke(stack, scope);
      return onTop instanceof Boolean && !((Boolean) onTop);
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** Finds a named field declared on a class or any superclass, made accessible. */
  private static Field findField(Class<?> cls, String name) {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException ignored) {
        // keep walking up
      }
    }
    return null;
  }

  /** Finds a named method with the given parameter count on a class or superclass, accessible. */
  private static Method findMethod(Class<?> cls, String name, int paramCount) {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      for (Method m : c.getDeclaredMethods()) {
        if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
          m.setAccessible(true);
          return m;
        }
      }
    }
    return null;
  }
}
