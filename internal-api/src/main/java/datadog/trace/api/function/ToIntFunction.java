package datadog.trace.api.function;

public interface ToIntFunction<T> {
  int applyAsInt(T obj);
}
