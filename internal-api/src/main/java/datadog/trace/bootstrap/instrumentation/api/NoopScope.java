package datadog.trace.bootstrap.instrumentation.api;

public final class NoopScope implements AgentScope {
  public static final NoopScope INSTANCE = new NoopScope();

  private NoopScope() {}

  @Override
  public AgentSpan span() {
    return NoopSpan.INSTANCE;
  }

  @Override
  public void close() {}
}
