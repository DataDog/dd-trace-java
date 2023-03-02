package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.experimental.ProfilingContext;

public interface ProfilingContextIntegration extends ProfilingContext {
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

  default ContinuableContext snapshot() {
    return NoOpSnapshot.INSTANCE;
  }

  final class NoOp implements ProfilingContextIntegration {

    public static final ProfilingContextIntegration INSTANCE =
        new ProfilingContextIntegration.NoOp();

    @Override
    public void setContextValue(String attribute, String value) {}

    @Override
    public void clearContextValue(String attribute) {}

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

  public static final class NoOpSnapshot implements ContinuableContext {
    public static final ContinuableContext INSTANCE = new NoOpSnapshot();

    public void activate() {}

    public void deactivate() {}
  }
}
