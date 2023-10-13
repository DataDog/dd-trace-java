package datadog.trace.bootstrap;

import datadog.trace.api.GenericClassValue;
import java.util.function.Function;

/**
 * An {@code InstanceStore} is a class global map for registering instances. This can be useful when
 * helper classes are injected into multiple class loaders but need to share unique keys of a type
 * from a common parent class loader.
 *
 * <p>The {@code InstanceStore} is backed by a {@code WeakMapContextStore} that has a max size of
 * 100 keys.
 */
public final class InstanceStore {
  private InstanceStore() {}

  private static final ClassValue<? super ContextStore<String, ?>> classInstanceStore =
      GenericClassValue.of(
          (Function<Class<?>, ContextStore<String, ?>>) input -> new WeakMapContextStore<>(100));

  @SuppressWarnings("unchecked")
  public static <T> ContextStore<String, T> of(Class<T> type) {
    return (ContextStore<String, T>) classInstanceStore.get(type);
  }
}
