package datadog.trace.bootstrap.debugger;

public interface DebuggerSpan {
  void finish();

  void setError(Throwable t);

  DebuggerSpan NOOP_SPAN = new NoopSpan();

  class NoopSpan implements DebuggerSpan {

    @Override
    public void finish() {}

    @Override
    public void setError(Throwable t) {}
  }
}
