package datadog.trace.api.function;

// TODO replace me when baselining against JDK8
public interface IntToObjFunction<T> {
  T apply(int value);
}
