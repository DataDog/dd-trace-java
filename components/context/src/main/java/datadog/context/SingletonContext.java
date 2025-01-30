package datadog.context;

import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** {@link Context} containing a single value. */
@ParametersAreNonnullByDefault
final class SingletonContext implements Context {
  final int index;
  final Object value;

  SingletonContext(int index, Object value) {
    this.index = index;
    this.value = value;
  }

  @Override
  @Nullable
  @SuppressWarnings("unchecked")
  public <V> V get(ContextKey<V> key) {
    requireNonNull(key, "Context key cannot be null");
    return this.index == key.index ? (V) this.value : null;
  }

  @Override
  public <V> Context with(ContextKey<V> secondKey, @Nullable V secondValue) {
    requireNonNull(secondKey, "Context key cannot be null");
    int secondIndex = secondKey.index;
    if (this.index == secondIndex) {
      return secondValue == null
          ? EmptyContext.INSTANCE
          : new SingletonContext(this.index, secondValue);
    } else {
      Object[] store = new Object[max(this.index, secondIndex) + 1];
      store[this.index] = this.value;
      store[secondIndex] = secondValue;
      return new IndexedContext(store);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SingletonContext that = (SingletonContext) o;
    return this.index == that.index && Objects.equals(this.value, that.value);
  }

  @Override
  public int hashCode() {
    int result = 31;
    result = 31 * result + this.index;
    result = 31 * result + Objects.hashCode(this.value);
    return result;
  }

  @Override
  public String toString() {
    return "SingletonContext{" + "index=" + this.index + ", value=" + this.value + '}';
  }
}
