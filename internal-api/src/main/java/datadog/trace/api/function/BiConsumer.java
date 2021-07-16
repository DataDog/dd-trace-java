package datadog.trace.api.function;

public interface BiConsumer<T, U> {
  void accept(T t, U u);
}
