package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.experimental.ProfilingContext;

public interface ProfilingContextIntegration extends ProfilingContext {
  /** Invoked when a trace first propagates to a thread */
  void onAttach();

  /** Invoked when a thread exits */
  void onDetach();

  void setContext(long rootSpanId, long spanId);

  final class NoOp implements ProfilingContextIntegration {

    public static final NoOp INSTANCE = new NoOp();

    @Override
    public void onAttach() {}

    @Override
    public void onDetach() {}

    @Override
    public void setContext(long rootSpanId, long spanId) {}

    @Override
    public void setContextValue(String attribute, String value) {}

    @Override
    public void clearContextValue(String attribute) {}
  }
}
