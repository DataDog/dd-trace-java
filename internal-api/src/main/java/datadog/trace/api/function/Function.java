package datadog.trace.api.function;

public interface Function<T, U> {
  U apply(T input);
}
