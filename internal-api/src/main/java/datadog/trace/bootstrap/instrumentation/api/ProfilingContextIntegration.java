package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.experimental.Profiling;
import datadog.trace.api.experimental.ProfilingContextSetter;
import datadog.trace.api.experimental.ProfilingScope;

public interface ProfilingContextIntegration extends Profiling {
  /** Invoked when a trace first propagates to a thread */
  void onAttach();

  /** Invoked when a thread exits */
  void onDetach();

  void setContext(ProfilerContext profilerContext);

  void clearContext();

  void setContext(long rootSpanId, long spanId);

  boolean isQueuingTimeEnabled();

  void recordQueueingTime(long duration);

  default int encode(CharSequence constant) {
    return 0;
  }

  final class NoOp implements ProfilingContextIntegration {

    public static final ProfilingContextIntegration INSTANCE =
        new ProfilingContextIntegration.NoOp();

    @Override
    public ProfilingContextSetter createContextSetter(String attribute) {
      return ProfilingContextSetter.NoOp.INSTANCE;
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
    public void setContext(ProfilerContext profilerContext) {}

    @Override
    public void clearContext() {}

    @Override
    public void setContext(long rootSpanId, long spanId) {}

    @Override
    public boolean isQueuingTimeEnabled() {
      return false;
    }

    @Override
    public void recordQueueingTime(long duration) {}
  }
}
