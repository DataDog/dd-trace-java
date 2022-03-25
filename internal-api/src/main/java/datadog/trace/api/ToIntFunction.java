package datadog.trace.api;

public interface ToIntFunction<T> {
  int applyAsInt(T input);
}
