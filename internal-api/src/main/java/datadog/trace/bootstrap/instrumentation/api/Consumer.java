package datadog.trace.bootstrap.instrumentation.api;

// java.util.function.Consumer
public interface Consumer<T> {
  void accept(T t);
}
