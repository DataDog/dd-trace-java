package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.*;

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

  /** Returns the current TSC tick count for the calling thread. */
  default long getCurrentTicks() {
    return 0L;
  }

  /**
   * Emits a TaskBlock event covering a blocking interval on the current thread.
   *
   * @param startTicks TSC tick at block entry
   * @param spanId the span ID active when blocking began
   * @param rootSpanId the local root span ID active when blocking began
   * @param blocker identity hash code of the blocking object, or 0 if none
   * @param unblockingSpanId the span ID of the thread that unblocked this thread, or 0 if unknown
   */
  default void recordTaskBlock(
      long startTicks, long spanId, long rootSpanId, long blocker, long unblockingSpanId) {}

  /**
   * Called when the current thread is about to enter {@code LockSupport.park*}. Native code can
   * suppress wall-clock signals for the park interval and record the start tick for off-CPU
   * analysis.
   */
  default void parkEnter(long spanId, long rootSpanId) {}

  /**
   * Called when the current thread has returned from {@code LockSupport.park*}. Clears the park
   * state and may emit a TaskBlock JFR event.
   */
  default void parkExit(long blocker, long unblockingSpanId) {}

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
