package datadog.context;

/** {@link ContextContinuation} capturing the empty (root) context. */
final class EmptyContextContinuation implements ContextContinuation {
  static final ContextContinuation INSTANCE = new EmptyContextContinuation();

  private EmptyContextContinuation() {}

  @Override
  public ContextContinuation hold() {
    return this;
  }

  @Override
  public Context context() {
    return EmptyContext.INSTANCE;
  }

  @Override
  public ContextScope resume() {
    return NoopContextScope.create(context());
  }

  @Override
  public void release() {}
}
