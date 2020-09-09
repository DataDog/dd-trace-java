package datadog.trace.api;

public interface Function<T, U> {
  U apply(T input);
}
