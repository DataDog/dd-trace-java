package datadog.context;

/** {@link Context} containing no values. */
final class EmptyContext implements Context {
  static final Context INSTANCE = new EmptyContext();

  @Override
  public <T> T get(ContextKey<T> key) {
    return null;
  }

  @Override
  public <T> Context with(ContextKey<T> key, T value) {
    return new SingletonContext(key.index, value);
  }
}
