package datadog.trace.bootstrap.instrumentation.api;

final class NoopContinuation implements AgentScope.Continuation {
  static final NoopContinuation INSTANCE = new NoopContinuation();

  private NoopContinuation() {}

  @Override
  public AgentScope.Continuation hold() {
    return this;
  }

  @Override
  public AgentScope activate() {
    return NoopScope.INSTANCE;
  }

  @Override
  public AgentSpan span() {
    return NoopSpan.INSTANCE;
  }

  @Override
  public void cancel() {}
}
