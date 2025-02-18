package datadog.trace.context;

public class NoopTraceScope implements TraceScope {
  public static final NoopTraceScope INSTANCE = new NoopTraceScope();

  public static class NoopContinuation implements Continuation {
    public static final NoopContinuation INSTANCE = new NoopContinuation();

    private NoopContinuation() {}

    @Override
    public Continuation hold() {
      return this;
    }

    @Override
    public TraceScope activate() {
      return NoopTraceScope.INSTANCE;
    }

    @Override
    public void cancel() {}
  }

  private NoopTraceScope() {}

  @Override
  public void close() {}
}
