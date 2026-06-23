package datadog.context;

/** {@link ContextScope} that has no effect on execution units. */
final class NoopContextScope implements ContextScope {
  private final Context context;

  NoopContextScope(Context context) {
    this.context = context;
  }

  @Override
  public Context context() {
    return context;
  }

  @Override
  public void close() {}
}
