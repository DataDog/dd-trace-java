package datadog.trace.agent.test.scopediag;

import datadog.context.ContextContinuation;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.NoopScope;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Forwards test-only Byte Buddy advice events to {@link ScopeDiagnostics}. */
public final class ScopeContinuationProbe {
  /**
   * Mirrors {@code ScopeContinuation.CANCELLED}. Reaching this value marks a resolved continuation;
   * {@code ScopeContinuationProbeTest} detects drift.
   */
  static final int CANCELLED = Integer.MIN_VALUE >> 1;

  private static volatile boolean recording = false;

  private static volatile Field sourceField;

  private static volatile Field scopeSourceField;
  private static volatile Field continuationField;
  private static volatile Field scopeManagerField;
  private static volatile Method scopeStackMethod;
  private static volatile Method checkTopMethod;

  private ScopeContinuationProbe() {}

  /** Installs the transformer once and starts recording. */
  static synchronized void enable() {
    ScopeContinuationTransformer.install();
    recording = true;
  }

  /** Stops recording without uninstalling the transformer. */
  static void disable() {
    recording = false;
  }

  public static void onCapture(Object self) {
    if (!recording) {
      return;
    }
    try {
      ContextContinuation continuation = (ContextContinuation) self;
      AgentSpan span = AgentSpan.fromContext(continuation.context());
      if (span != null) {
        ScopeDiagnostics.recordCapture(
            continuation, span.getTraceId(), span.getSpanId(), spanName(span), sourceOf(self));
      }
    } catch (Throwable ignored) {
      // Diagnostics must never affect the tracer.
    }
  }

  public static void onActivate(Object self, Object returnedScope, long activateNanos) {
    if (!recording) {
      return;
    }
    try {
      ContextContinuation continuation = (ContextContinuation) self;
      if (returnedScope == NoopScope.INSTANCE) {
        // A noop result may indicate activation after resolution.
        ScopeDiagnostics.recordActivateFailed(continuation);
        return;
      }
      AgentSpan span = AgentSpan.fromContext(continuation.context());
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

  public static void onResolve(
      Object self, String method, int countBefore, int countAfter, long resolveNanos) {
    if (!recording) {
      return;
    }
    if (countAfter != CANCELLED) {
      return;
    }
    // release discards; cancelFromContinuedScopeClose finishes. Its slow path delegates to release,
    // so a multi-activation finish can appear as a cancellation.
    boolean cancelled = "release".equals(method);
    try {
      ContextContinuation continuation = (ContextContinuation) self;
      ScopeDiagnostics.recordResolve(
          continuation, cancelled, resolveNanos, countBefore == CANCELLED);
    } catch (Throwable ignored) {
    }
  }

  public static void onRootWritten(Object traceId) {
    if (!recording) {
      return;
    }
    try {
      ScopeDiagnostics.recordRootWritten((DDTraceId) traceId);
    } catch (Throwable ignored) {
    }
  }

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

  public static void onScopeClose(Object scope) {
    if (!recording) {
      return;
    }
    try {
      ScopeDiagnostics.recordScopeClose(scope);
    } catch (Throwable ignored) {
    }
  }

  public static void onDeferredScopeCleanup(Object scope) {
    if (!recording) {
      return;
    }
    try {
      ScopeDiagnostics.recordDeferredScopeCleanup(scope);
    } catch (Throwable ignored) {
    }
  }

  /** Records an out-of-order close when the internal stack can be inspected. */
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

  /** Copies the possibly mutable span name. */
  private static String spanName(AgentSpan span) {
    try {
      CharSequence name = span.getSpanName();
      return name == null ? null : name.toString();
    } catch (Throwable ignored) {
      return null;
    }
  }

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

  private static ContextContinuation continuationOf(Object scope) {
    try {
      Field field = continuationField;
      if (field == null) {
        field = findField(scope.getClass(), "continuation");
        continuationField = field;
      }
      if (field == null || !field.getDeclaringClass().isInstance(scope)) {
        return null;
      }
      Object value = field.get(scope);
      return value instanceof ContextContinuation ? (ContextContinuation) value : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

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

  private static Field findField(Class<?> cls, String name) {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException ignored) {
      }
    }
    return null;
  }

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
