package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
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

  /**
   * Applies {@code context} to the current thread's profiler context. Default is a no-op: only
   * integrations that key profiler context by the running (carrier) thread need this, and only when
   * driven by the legacy context manager, where the virtual-thread instrumentation seeds the scope
   * stack once and calls this on each subsequent mount rather than swapping.
   */
  default void setContext(Context context) {}

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
