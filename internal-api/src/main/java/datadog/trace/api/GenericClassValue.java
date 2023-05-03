package datadog.trace.api;

import java.util.function.Function;

public final class GenericClassValue<T> extends ClassValue<T> {

  @SuppressWarnings("unchecked")
  public static <T> ClassValue<T> constructing(Class<?> type) {
    return new GenericClassValue<>((Function<Class<?>, T>) Functions.newInstanceOf(type));
  }

  public static <T> ClassValue<T> of(Function<Class<?>, T> function) {
    return new GenericClassValue<>(function);
  }

  private final Function<Class<?>, T> function;

  private GenericClassValue(Function<Class<?>, T> function) {
    this.function = function;
  }

  @Override
  protected final T computeValue(Class<?> type) {
    return function.apply(type);
  }
}
