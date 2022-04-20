package datadog.trace.api.function;

public interface ObjToIntFunction<T> {
  int apply(T obj);
}
