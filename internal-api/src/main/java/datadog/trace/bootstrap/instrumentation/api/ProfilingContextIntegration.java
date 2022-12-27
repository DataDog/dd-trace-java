package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilingContextIntegration {
  /** Invoked when a trace first propagates to a thread */
  void onAttach(int tid);

  /** Invoked when a thread exits */
  void onDetach(int tid);

  void setContext(int tid, long rootSpanId, long spanId);

  int getNativeThreadId();

  final class NoOp implements ProfilingContextIntegration {

    public static final NoOp INSTANCE = new NoOp();

    @Override
    public void onAttach(int tid) {}

    @Override
    public void onDetach(int tid) {}

    @Override
    public void setContext(int tid, long rootSpanId, long spanId) {}

    @Override
    public int getNativeThreadId() {
      return -1;
    }
  }
}
