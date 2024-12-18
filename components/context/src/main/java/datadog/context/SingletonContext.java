package datadog.context;

import static java.lang.Math.max;

import java.util.Objects;

/** {@link Context} containing a single value. */
final class SingletonContext implements Context {
  private final int index;
  private final Object value;

  SingletonContext(int index, Object value) {
    this.index = index;
    this.value = value;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <V> V get(ContextKey<V> key) {
    return index == key.index ? (V) value : null;
  }

  @Override
  public <V> Context with(ContextKey<V> secondKey, V secondValue) {
    int secondIndex = secondKey.index;
    if (index == secondIndex) {
      return new SingletonContext(index, secondValue);
    } else {
      Object[] store = new Object[max(index, secondIndex) + 1];
      store[index] = value;
      store[secondIndex] = secondValue;
      return new IndexedContext(store);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SingletonContext that = (SingletonContext) o;
    return index == that.index && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    int result = 31;
    result = 31 * result + index;
    result = 31 * result + Objects.hashCode(value);
    return result;
  }
}
