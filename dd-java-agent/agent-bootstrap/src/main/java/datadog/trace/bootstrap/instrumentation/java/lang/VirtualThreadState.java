package datadog.trace.bootstrap.instrumentation.java.lang;

import datadog.context.Context;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

/**
 * Holds the context and continuation for a virtual thread.
 *
 * <p>With the legacy context manager, {@code swap()} rebuilds the whole scope stack on every
 * mount/unmount, which is costly on the virtual-thread park/unpark hot path. There the context is
 * seeded once on the first mount — it then follows the thread across park/unpark and carrier
 * migration via its virtual-thread-aware {@code ThreadLocal} scope stack — and the profiler context
 * (which is keyed by carrier thread) is re-applied on each subsequent mount and cleared on unmount.
 *
 * <p>With the new context manager {@code swap()} is cheap and drives the profiler through its
 * context listener, so we simply swap in on mount and out on unmount.
 */
public final class VirtualThreadState {
  private static final boolean LEGACY_CONTEXT_MANAGER =
      InstrumenterConfig.get().isLegacyContextManagerEnabled();

  /** The virtual thread's saved context (scope stack snapshot). */
  private Context context;

  /** Prevents the enclosing context scope from completing before the virtual thread finishes. */
  private final Continuation continuation;

  /** The carrier thread's saved context, set between mount and unmount. */
  private Context previousContext;

  /** Determines whenever the context has been already set for the first mount */
  private boolean seeded;

  public VirtualThreadState(Context context, Continuation continuation) {
    this.context = context;
    this.continuation = continuation;
  }

  public void onMount() {
    if (LEGACY_CONTEXT_MANAGER) {
      if (!seeded) {
        // First mount also applies the profiler context to the carrier.
        context.swap();
        seeded = true;
        context = null;
      } else {
        AgentTracer.get().getProfilingContext().setContext(Context.current());
      }
    } else {
      previousContext = context.swap();
    }
  }

  public void onUnmount() {
    if (LEGACY_CONTEXT_MANAGER) {
      AgentTracer.get().getProfilingContext().clearContext();
    } else if (previousContext != null) {
      context = previousContext.swap();
      previousContext = null;
    }
  }

  /** Called on termination: releases the trace continuation. */
  public void onTerminate() {
    if (this.continuation != null) {
      this.continuation.cancel();
    }
  }
}
