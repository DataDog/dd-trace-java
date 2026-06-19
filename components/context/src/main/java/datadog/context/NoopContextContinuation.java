package datadog.context;

/** {@link ContextContinuation} that has no effect on execution units. */
final class NoopContextContinuation implements ContextContinuation {
  static final ContextContinuation ROOT_CONTINUATION = new NoopContextContinuation(Context.root());

  private final Context context;

  private NoopContextContinuation(Context context) {
    this.context = context;
  }

  @Override
  public ContextContinuation hold() {
    return this;
  }

  @Override
  public Context context() {
    return context;
  }

  @Override
  public ContextScope resume() {
    return NoopContextScope.create(context);
  }

  @Override
  public void release() {}
}
