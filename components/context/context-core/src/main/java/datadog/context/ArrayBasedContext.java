package datadog.context;

import static java.lang.Math.max;
import static java.util.Arrays.copyOfRange;

import java.util.Arrays;
import javax.annotation.Nonnull;

/** An array-based {@link Context} implementation. */
public class ArrayBasedContext implements Context {
  private static final ArrayBasedContext EMPTY = new ArrayBasedContext(new Object[0]);
  /** The generic store. Values are indexed by their {@link ContextKey#index()}. */
  private final Object[] store;

  private ArrayBasedContext(Object[] store) {
    this.store = store;
  }

  /**
   * Get an empty context.
   *
   * @return An empty context.
   */
  public static ArrayBasedContext empty() {
    return EMPTY;
  }

  // TODO USEFUL? IF SO, ENABLE AND DOCUMENT
  //  public static ArrayBasedContext fromMap(Map<ContextKey<?>, Object> content) {
  //    if (content.isEmpty()) {
  //      return empty();
  //    }
  //    int length = content.keySet().stream().mapToInt(ContextKey::index).max().orElse(0) + 1;
  //    Object[] store = new Object[length];
  //    content.forEach((key, value) -> store[key.index()] = value);
  //    return new ArrayBasedContext(store);
  //  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T get(@Nonnull ContextKey<T> key) {
    return key != null && key.index() < this.store.length ? (T) this.store[key.index()] : null;
  }

  @Nonnull
  @Override
  public <T> ArrayBasedContext with(@Nonnull ContextKey<T> key, T value) {
    if (key == null) {
      return this;
    }
    Object[] newStore = copyOfRange(this.store, 0, max(this.store.length, key.index() + 1));
    newStore[key.index()] = value;
    return new ArrayBasedContext(newStore);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ArrayBasedContext that = (ArrayBasedContext) o;
    return Arrays.equals(this.store, that.store);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(this.store);
  }

  @Override
  public String toString() {
    return "Context@" + Integer.toHexString(hashCode()) + '{' + Arrays.toString(this.store) + '}';
  }
}
