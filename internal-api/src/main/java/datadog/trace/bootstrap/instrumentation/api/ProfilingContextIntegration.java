// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.*;

public interface ProfilingContextIntegration extends Profiling, EndpointCheckpointer, Timer {
  /** Native {@code OSThreadState::SLEEPING}; used for {@code Thread.sleep} intervals. */
  int BLOCKING_STATE_SLEEPING = 7;

  /**
   * invoked when the profiler is started, implementations must not initialise JFR before this is
   * called.
   */
  default void onStart() {}

  /** Invoked when a trace first propagates to a thread */
  default void onAttach() {}

  /** Invoked when a thread exits */
  default void onDetach() {}

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
   * Starts a synchronous TaskBlock interval on the current platform thread.
   *
   * @param state native blocking-state identifier
   * @return an opaque token for {@link #endTaskBlock(long, long, long)}, or {@code 0} when the
   *     interval was not accepted
   */
  default long beginTaskBlock(int state) {
    return 0L;
  }

  /**
   * Completes a TaskBlock interval accepted by {@link #beginTaskBlock(int)}.
   *
   * @param token nonzero token returned by {@code beginTaskBlock}
   * @param blocker identity hash of the blocking object, or {@code 0} when absent
   * @param unblockingSpanId span that ended the interval, or {@code 0} when unavailable
   * @return whether the native interval was completed and emitted
   */
  default boolean endTaskBlock(long token, long blocker, long unblockingSpanId) {
    return false;
  }

  String name();

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
