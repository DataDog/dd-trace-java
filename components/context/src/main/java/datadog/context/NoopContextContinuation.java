package datadog.context;

/**
 * {@link ContextContinuation} that has no effect on execution units.
 */
final class NoopContextContinuation implements ContextContinuation, ContextScope {
  private final Context context;

  NoopContextContinuation(Context context) {
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
    // acts as no-op scope, avoiding allocation
    return this;
  }

  @Override
  public void release() {}

  @Override
  public void close() {}
}
