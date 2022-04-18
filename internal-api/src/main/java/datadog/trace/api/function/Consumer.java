package datadog.trace.api.function;

public interface Consumer<T> {
  void accept(T t);
}
