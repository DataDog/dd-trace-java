package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilingContextIntegration {
  /** Invoked when a trace first propagates to a thread */
  void onAttach();

  /** Invoked when a thread exits */
  void onDetach();

  void setContext(long rootSpanId, long spanId);

  void setContextValue(String attribute, String value);

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
  }
}
