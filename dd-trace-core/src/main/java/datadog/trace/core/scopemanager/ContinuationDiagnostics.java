package datadog.trace.core.scopemanager;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

/**
 * Test-time diagnostic seam for tracking the lifecycle of scope {@link AgentScope.Continuation
 * continuations} as they are captured on one thread and activated/cancelled on another.
 *
 * <p>This class is inert in production: the {@link Listener} is {@code null} unless test code
 * installs one via {@link #install(Listener)}. Each notification site reads the listener once
 * through a single {@code volatile} read and skips the call when none is installed, so the cost
 * when disabled is one volatile read per scope lifecycle event with no allocation and no behavior
 * change.
 *
 * <p>The seam intentionally carries only continuation <em>identity</em> plus the trace/span ids and
 * {@code source}. Wall-clock time, the current thread, and the capturing/activating/resolving stack
 * trace are gathered by the listener itself: notifications are synchronous and run on the event's
 * own thread, so the listener observes the correct thread and call stack.
 */
public final class ContinuationDiagnostics {
  /**
   * Receives scope-continuation lifecycle events. Implementations live in test code and must never
   * throw back into the tracer (callers do not guard against exceptions).
   */
  public interface Listener {
    /** A continuation was captured (registered) and may be transferred to another thread. */
    void onCapture(AgentScope.Continuation id, DDTraceId traceId, long spanId, byte source);

    /** A captured continuation was activated, resuming the scope (possibly on another thread). */
    void onActivate(AgentScope.Continuation id, DDTraceId traceId, long spanId, byte source);

    /**
     * A continuation was resolved: either finished normally (all activations closed or a clean
     * cancel) or cancelled while activations/holds were outstanding.
     *
     * @param cancelled {@code true} if resolved via the cancel-with-outstanding-work path.
     */
    void onResolve(AgentScope.Continuation id, boolean cancelled);

    /** The root span of the given trace has been written (the trace's root close signal). */
    void onRootWritten(DDTraceId traceId);
  }

  private static volatile Listener listener;

  private ContinuationDiagnostics() {}

  /** Installs the diagnostic listener. Intended for test code only. */
  public static void install(Listener listener) {
    ContinuationDiagnostics.listener = listener;
  }

  /** Removes any installed diagnostic listener, returning the seam to its inert state. */
  public static void clear() {
    ContinuationDiagnostics.listener = null;
  }

  static Listener listener() {
    return listener;
  }

  /**
   * Notifies the installed listener (if any) that the root span of the given trace has been
   * written. Safe to call from any package; inert when no listener is installed.
   */
  public static void notifyRootWritten(DDTraceId traceId) {
    final Listener l = listener;
    if (l != null) {
      l.onRootWritten(traceId);
    }
  }
}
