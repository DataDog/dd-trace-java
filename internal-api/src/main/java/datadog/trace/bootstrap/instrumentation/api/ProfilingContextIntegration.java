// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.Profiling;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timer;
import datadog.trace.api.profiling.Timing;

public interface ProfilingContextIntegration extends Profiling, EndpointCheckpointer, Timer {
  /**
   * invoked when the profiler is started, implementations must not initialise JFR before this is
   * called.
   */
  default void onStart() {}

  /** Invoked when a trace first propagates to a thread */
  default void onAttach() {}

  /** Invoked when a thread exits */
  default void onDetach() {}

  /**
   * Binds {@code context} to profiler state associated with the current carrier thread.
   *
   * <p>The default implementation is a no-op. Integrations that return {@code true} from {@link
   * #isThreadContextBindingRequired()} must override this method and treat {@link Context#root()}
   * as clearing the current binding.
   *
   * @param context context to bind
   */
  default void setContext(Context context) {}

  /** Whether profiler context must be rebound when a virtual thread changes carrier threads. */
  default boolean isThreadContextBindingRequired() {
    return false;
  }

  default Stateful newScopeState(ProfilerContext profilerContext) {
    return Stateful.DEFAULT;
  }

  default int encode(CharSequence constant) {
    return 0;
  }

  default int encodeOperationName(CharSequence constant) {
    return 0;
  }

  default int encodeResourceName(CharSequence constant) {
    return 0;
  }

  /**
   * Called before the current thread enters {@code LockSupport.park*}.
   *
   * @return {@code true} when the integration accepted the entry dispatch and therefore requires a
   *     matching {@link #parkExit(long, long)}, even if profiling stops before the park returns
   */
  default boolean parkEnter() {
    return false;
  }

  /** Completes a {@code LockSupport.park*} dispatch accepted by {@link #parkEnter()}. */
  default void parkExit(long blocker, long unblockingSpanId) {}

  /**
   * Whether {@code LockSupport.unpark} callers should record best-effort span attribution for the
   * target thread. This is a cheap gate on a very hot path: when it returns {@code false} the
   * instrumentation skips the active span lookup entirely.
   *
   * @return {@code true} when a subsequent {@link #parkExit(long, long)} could consume the recorded
   *     unblocking span id
   */
  default boolean isUnparkAttributionEnabled() {
    return false;
  }

  String name();

  /**
   * Registers a one-shot callback to run once this integration is actually able to label context;
   * runs inline if already available, or never if a deferred construction fails.
   */
  default void whenAvailable(Runnable callback) {
    callback.run();
  }

  final class NoOp implements ProfilingContextIntegration {

    public static final ProfilingContextIntegration INSTANCE =
        new ProfilingContextIntegration.NoOp();

    @Override
    public ProfilingContextAttribute createContextAttribute(String attribute) {
      return ProfilingContextAttribute.NoOp.INSTANCE;
    }

    @Override
    public ProfilingScope newScope() {
      return ProfilingScope.NO_OP;
    }

    @Override
    public void onAttach() {}

    @Override
    public void onDetach() {}

    @Override
    public String name() {
      return "none";
    }

    @Override
    public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public Timing start(TimerType type) {
      return Timing.NoOp.INSTANCE;
    }
  }
}
