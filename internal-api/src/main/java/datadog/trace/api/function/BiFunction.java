package datadog.trace.api.function;

public interface BiFunction<T, U, R> {
  R apply(T t, U u);
}
