package datadog.trace.core.util;

import datadog.trace.api.function.Consumer;

public class GatedConsumer<T> implements Consumer<T> {
  private final Consumer<T> delegate;
  private boolean hasValue;
  private T value;

  public GatedConsumer(Consumer<T> delegate) {
    this.delegate = delegate;
  }

  @Override
  public void accept(T t) {
    value = t;
    hasValue = true;
  }

  public void release() {
    if (hasValue) {
      delegate.accept(value);
      hasValue = false;
    }
  }
}
