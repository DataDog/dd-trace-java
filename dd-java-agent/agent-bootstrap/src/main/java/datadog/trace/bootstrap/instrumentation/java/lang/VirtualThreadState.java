package datadog.trace.bootstrap.instrumentation.java.lang;

import static datadog.environment.JavaVirtualMachine.isJavaVersion;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * Holds the context and continuation for a virtual thread.
 *
 * <p>This state belongs to the Java 21 virtual-thread instrumentation and is kept in a context
 * store because {@code java.lang.VirtualThread} is loaded before the agent can inject fields.
 *
 * <p>The legacy context manager's {@code swap()} wraps the current scope stack together with the
 * context so the original stack can be restored when the context is swapped back; doing that on
 * every mount/unmount is costly on the virtual-thread park/unpark hot path. On JDK 22 and later the
 * context is instead seeded once when the virtual thread starts running, then follows the thread
 * across park/unpark and carrier migration via its virtual-thread-aware {@code ThreadLocal} scope
 * stack. The profiler context, which is keyed by carrier thread, is rebound on mount and cleared on
 * unmount.
 *
 * <p>JDK 21 retains the per-mount path because early update releases enter {@code run(Runnable)} on
 * the carrier thread before the first mount. Using one implementation for all JDK 21 updates avoids
 * relying on the internal lifecycle change introduced in later updates.
 *
 * <p>With the new context manager {@code swap()} is cheap and drives the profiler through its
 * context listener, so we simply swap in on mount and out on unmount.
 */
public final class VirtualThreadState {
  // note: cws is relying on scope listener. This is disabled by default but when enabled
  // let's use the full swap logic since otherwise listeners won't be called
  private static final boolean USE_PER_MOUNT_CONTEXT =
      isJavaVersion(21)
          || !InstrumenterConfig.get().isLegacyContextManagerEnabled()
          || Config.get().isCwsEnabled();

  /** The virtual thread's saved context (scope stack snapshot). */
  private Context context;

  /** Prevents the enclosing context scope from completing before the virtual thread finishes. */
  private final ContextContinuation continuation;

  /** The carrier thread's saved context, set between mount and unmount. */
  private Context previousContext;

  public VirtualThreadState(Context context, ContextContinuation continuation) {
    this.context = context;
    this.continuation = continuation;
  }

  /** Whether context propagation must retain the state-backed mount/unmount path. */
  public static boolean usePerMountContext() {
    return USE_PER_MOUNT_CONTEXT;
  }

  /** Seeds context once at the start of the virtual thread's continuation. */
  public void onRun() {
    previousContext = context.swap();
    context = null;
  }

  /** Restores the context that preceded this virtual thread's continuation. */
  public void afterRun() {
    if (previousContext != null) {
      previousContext.swap();
      previousContext = null;
    }
  }

  /** Rebinds carrier-local profiler state from context already owned by the virtual thread. */
  public static void onMountWithoutStore() {
    ProfilingContextIntegration profilingContext = AgentTracer.get().getProfilingContext();
    if (profilingContext.isThreadContextBindingRequired()) {
      profilingContext.setContext(Context.current());
    }
  }

  /** Clears carrier-local profiler state before the virtual thread unmounts. */
  public static void onUnmountWithoutStore() {
    ProfilingContextIntegration profilingContext = AgentTracer.get().getProfilingContext();
    if (profilingContext.isThreadContextBindingRequired()) {
      profilingContext.setContext(Context.root());
    }
  }

  /** Activates the virtual thread's context for the state-backed per-mount path. */
  public void onMount() {
    previousContext = context.swap();
  }

  /** Restores the context that preceded the state-backed mount. */
  public void onUnmount() {
    if (previousContext != null) {
      context = previousContext.swap();
      previousContext = null;
    }
  }

  public void onTerminate() {
    if (this.continuation != null) {
      this.continuation.release();
    }
  }
}
