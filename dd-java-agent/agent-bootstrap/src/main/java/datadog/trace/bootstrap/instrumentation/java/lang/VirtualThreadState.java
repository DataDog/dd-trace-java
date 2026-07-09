package datadog.trace.bootstrap.instrumentation.java.lang;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

/**
 * The virtual thread's scope stack lives in a virtual-thread-aware {@code ThreadLocal}, so it is
 * seeded once on the first mount and then follows the thread across park/unpark and carrier
 * migration on its own. ddprof's profiler context is keyed by carrier thread instead, so it is
 * re-bound on mount and cleared on unmount when a carrier-bound profiling integration is active.
 */
public final class VirtualThreadState {
  private Context seedContext;
  // Keeps the enclosing trace alive until the virtual thread finishes.
  private final Continuation continuation;
  private boolean seeded;

  public VirtualThreadState(Context seedContext, Continuation continuation) {
    this.seedContext = seedContext;
    this.continuation = continuation;
  }

  public void onMount() {
    if (!seeded) {
      // First mount also applies the profiler context to the carrier.
      seedContext.swap();
      seeded = true;
      seedContext = null;
    } else {
      AgentTracer.get().rebindProfilingContextToCarrier();
    }
  }

  public void onUnmount() {
    AgentTracer.get().unbindProfilingContextFromCarrier();
  }

  public void onTerminate() {
    if (this.continuation != null) {
      this.continuation.cancel();
    }
  }
}
