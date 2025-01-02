package datadog.context;

import static java.lang.Math.max;
import static java.util.Arrays.copyOfRange;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** {@link Context} containing many values. */
@ParametersAreNonnullByDefault
final class IndexedContext implements Context {
  private final Object[] store;

  IndexedContext(Object[] store) {
    this.store = store;
  }

  @Override
  @Nullable
  @SuppressWarnings("unchecked")
  public <T> T get(ContextKey<T> key) {
    requireNonNull(key, "Context key cannot be null");
    int index = key.index;
    return index < store.length ? (T) store[index] : null;
  }

  @Override
  public <T> Context with(ContextKey<T> key, @Nullable T value) {
    requireNonNull(key, "Context key cannot be null");
    int index = key.index;
    Object[] newStore = copyOfRange(store, 0, max(store.length, index + 1));
    newStore[index] = value;
    return new IndexedContext(newStore);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    IndexedContext that = (IndexedContext) o;
    return Arrays.equals(store, that.store);
  }

  @Override
  public int hashCode() {
    int result = 31;
    result = 31 * result + Arrays.hashCode(store);
    return result;
  }
}
