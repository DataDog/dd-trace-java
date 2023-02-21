package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilingContextIntegration
    extends datadog.trace.api.experimental.ProfilingContext {
  /** Invoked when a trace first propagates to a thread */
  void onAttach();

  /** Invoked when a thread exits */
  void onDetach();

  void setContext(long rootSpanId, long spanId);

  boolean isQueuingTimeEnabled();

  void recordQueueingTime(long duration);

  int[] createContextStorage(CharSequence operationName);

  void updateOperationName(CharSequence operationName, int[] storage, boolean active);

  void setContext(int offset, int value);

  void clearContext(int offset);

  final class NoOp implements ProfilingContextIntegration {

    private static final int[] EMPTY = new int[0];

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
    public void setContext(long rootSpanId, long spanId) {}

    @Override
    public boolean isQueuingTimeEnabled() {
      return false;
    }

    @Override
    public void recordQueueingTime(long duration) {}

    @Override
    public int[] createContextStorage(CharSequence operationName) {
      return EMPTY;
    }

    @Override
    public void updateOperationName(CharSequence operationName, int[] storage, boolean active) {}

    @Override
    public void setContext(int offset, int value) {}

    @Override
    public void clearContext(int offset) {}
  }
}
