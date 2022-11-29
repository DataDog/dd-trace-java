package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilingContextIntegration {
  /** Invoked when a trace first propagates to a thread */
  void onAttach();

  /** Invoked when a thread exits */
  void onDetach();

  void setContext(int tid, long rootSpanId, long spanId);

  int getNativeThreadId();

  final class NoOp implements ProfilingContextIntegration {

    public static final NoOp INSTANCE = new NoOp();

    @Override
    public void onAttach() {}

    @Override
    public void onDetach() {}

    @Override
    public void setContext(int tid, long rootSpanId, long spanId) {}

    @Override
    public int getNativeThreadId() {
      return -1;
    }
  }
}
