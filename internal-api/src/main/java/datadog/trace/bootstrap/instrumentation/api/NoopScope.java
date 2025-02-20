package datadog.trace.bootstrap.instrumentation.api;

final class NoopScope implements AgentScope {
  static final NoopScope INSTANCE = new NoopScope();

  private NoopScope() {}

  @Override
  public AgentSpan span() {
    return NoopSpan.INSTANCE;
  }

  @Override
  public byte source() {
    return 0;
  }

  @Override
  public Continuation capture() {
    return NoopContinuation.INSTANCE;
  }

  @Override
  public void close() {}
}
