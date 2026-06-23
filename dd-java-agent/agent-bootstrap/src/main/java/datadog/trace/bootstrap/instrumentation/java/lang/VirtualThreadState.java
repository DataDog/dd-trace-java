package datadog.trace.bootstrap.instrumentation.java.lang;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;

/**
 * This class holds the saved context and scope continuation for a virtual thread.
 *
 * <p>Used by java-lang-21.0 {@code VirtualThreadInstrumentation} to swap the entire scope stack on
 * mount/unmount.
 */
public final class VirtualThreadState {
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

  /** Called on mount: swaps the virtual thread's context into the carrier thread. */
  public void onMount() {
    this.previousContext = this.context.swap();
  }

  /** Called on unmount: restores the carrier thread's original context. */
  public void onUnmount() {
    if (this.previousContext != null) {
      this.context = this.previousContext.swap();
      this.previousContext = null;
    }
  }

  /** Called on termination: releases the trace continuation. */
  public void onTerminate() {
    if (this.continuation != null) {
      this.continuation.cancel();
    }
  }
}
