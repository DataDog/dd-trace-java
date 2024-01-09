package datadog.trace.context;

public class NoopTraceScope implements TraceScope {
  public static final NoopTraceScope INSTANCE = new NoopTraceScope();

  public static class NoopContinuation implements Continuation {
    public static final NoopContinuation INSTANCE = new NoopContinuation();

    private NoopContinuation() {}

    @Override
    public TraceScope activate() {
      return NoopTraceScope.INSTANCE;
    }

    @Override
    public void cancel() {}
  }

  private NoopTraceScope() {}

  @Override
  public Continuation capture() {
    return NoopContinuation.INSTANCE;
  }

  @Override
  public Continuation captureConcurrent() {
    return null;
  }

  @Override
  public void close() {}

  @Override
  public boolean isAsyncPropagating() {
    return false;
  }

  @Override
  public void setAsyncPropagation(boolean value) {}
}
