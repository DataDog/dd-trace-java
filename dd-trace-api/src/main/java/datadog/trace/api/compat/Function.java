package datadog.trace.api.compat;

public interface Function<T, U> {
  U apply(T input);
}
