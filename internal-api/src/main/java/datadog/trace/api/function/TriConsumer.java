package datadog.trace.api.function;

public interface TriConsumer<S, T, U> {
  void accept(S s, T t, U u);
}
