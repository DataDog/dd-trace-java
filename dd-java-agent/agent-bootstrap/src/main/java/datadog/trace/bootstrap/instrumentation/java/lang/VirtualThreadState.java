package datadog.trace.bootstrap.instrumentation.java.lang;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

/**
 * Holds the saved context and trace continuation for a virtual thread. Used by
 * VirtualThreadInstrumentation to swap the entire scope stack on mount/unmount, rather than
 * activating/closing individual scopes.
 *
 * <p>Modeled on FiberContext (ZIO instrumentation) and DatadogThreadContextElement (Kotlin
 * coroutines).
 */
public final class VirtualThreadState {
  /** The virtual thread's saved context (scope stack snapshot). */
  private Context context;

  /** Prevents the enclosing trace from completing before the virtual thread finishes. */
  private final AgentScope.Continuation continuation;

  /** The carrier thread's saved context, set between mount and unmount. */
  private Context previousContext;

  public VirtualThreadState(Context context, AgentScope.Continuation continuation) {
    this.context = context;
    this.continuation = continuation;
  }

  /** Called on mount: swaps the virtual thread's context into the carrier thread. */
  public void onMount() {
    previousContext = context.swap();
  }

  /** Called on unmount: restores the carrier thread's original context. */
  public void onUnmount() {
    if (previousContext != null) {
      context = previousContext.swap();
      previousContext = null;
    }
  }

  /** Called on termination: releases the trace continuation. */
  public void onTerminate() {
    if (continuation != null) {
      continuation.cancel();
    }
  }
}
