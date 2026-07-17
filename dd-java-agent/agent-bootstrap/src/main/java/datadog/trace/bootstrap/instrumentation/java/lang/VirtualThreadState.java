package datadog.trace.bootstrap.instrumentation.java.lang;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

/**
 * Holds the context and continuation for a virtual thread.
 *
 * <p>The legacy context manager's {@code swap()} wraps the current scope stack together with the
 * context so the original stack can be restored when the context is swapped back; doing that on
 * every mount/unmount is costly on the virtual-thread park/unpark hot path. So instead the context
 * is seeded once on the first mount, then follows the thread across park/unpark and carrier
 * migration via its virtual-thread-aware {@code ThreadLocal} scope stack, while the profiler
 * context (which is keyed by carrier thread) is re-applied on each subsequent mount and restored on
 * unmount.
 *
 * <p>With the new context manager {@code swap()} is cheap and drives the profiler through its
 * context listener, so we simply swap in on mount and out on unmount.
 */
public final class VirtualThreadState {
  // note: cws is relying on scope listener. This is disabled by default but when enabled
  // let's use the full swap logic since otherwise listeners won't be called
  private static final boolean USE_SIMPLE_SWAP =
      !InstrumenterConfig.get().isLegacyContextManagerEnabled() || Config.get().isCwsEnabled();

  /** The virtual thread's saved context (scope stack snapshot). */
  private Context context;

  /** Prevents the enclosing context scope from completing before the virtual thread finishes. */
  private final Continuation continuation;

  /** The carrier thread's saved context, set between mount and unmount. */
  private Context previousContext;

  public VirtualThreadState(Context context, Continuation continuation) {
    this.context = context;
    this.continuation = continuation;
  }

  public void onMount() {
    if (USE_SIMPLE_SWAP) {
      previousContext = context.swap();
    } else {
      if (context != null) {
        // First mount also applies the profiler context to the carrier.
        previousContext = context.swap();
        context = null;
      } else {
        AgentTracer.get().getProfilingContext().setContext(Context.current());
      }
    }
  }

  public void onUnmount() {
    if (previousContext != null) {
      if (USE_SIMPLE_SWAP) {
        context = previousContext.swap();
        previousContext = null;
      } else {
        AgentTracer.get().getProfilingContext().setContext(previousContext);
      }
    }
  }

  public void onTerminate() {
    if (this.continuation != null) {
      this.continuation.cancel();
    }
  }
}
