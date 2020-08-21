package datadog.trace.bootstrap.instrumentation.api;

public interface Function<T, U> {
  U apply(T input);
}
