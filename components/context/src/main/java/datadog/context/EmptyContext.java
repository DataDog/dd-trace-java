package datadog.context;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** {@link Context} containing no values. */
@ParametersAreNonnullByDefault
final class EmptyContext implements Context {
  static final Context INSTANCE = new EmptyContext();

  @Override
  @Nullable
  public <T> T get(ContextKey<T> key) {
    return null;
  }

  @Override
  public <T> Context with(ContextKey<T> key, @Nullable T value) {
    requireNonNull(key, "Context key cannot be null");
    if (value == null) {
      return this;
    }
    return new SingletonContext(key.index, value);
  }

  @Override
  public String toString() {
    return "EmptyContext{}";
  }
}
