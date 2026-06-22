package datadog.context;

/** {@link ContextScope} that has no effect on execution units. */
final class NoopContextScope implements ContextScope {
  static final ContextScope ROOT_SCOPE = new NoopContextScope(Context.root());

  static ContextScope create(Context context) {
    return context == Context.root() ? ROOT_SCOPE : new NoopContextScope(context);
  }

  private final Context context;

  private NoopContextScope(Context context) {
    this.context = context;
  }

  @Override
  public Context context() {
    return context;
  }

  @Override
  public void close() {}
}
