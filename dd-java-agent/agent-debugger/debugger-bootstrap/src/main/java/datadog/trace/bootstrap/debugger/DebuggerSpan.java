package datadog.trace.bootstrap.debugger;

public interface DebuggerSpan {
  void finish();

  DebuggerSpan NOOP_SPAN = new NoopSpan();

  class NoopSpan implements DebuggerSpan {

    @Override
    public void finish() {}
  }
}
