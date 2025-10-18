package datadog.trace.bootstrap;

import datadog.trace.api.GenericClassValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * An {@code InstanceStore} is a class global map for registering instances. This can be useful when
 * helper classes are injected into multiple class loaders and need to share instances of a type
 * from a common parent class loader.
 *
 * <p>Instance keys are expected to be string literals, defined as constants in the helper classes.
 */
public final class InstanceStore<T> implements ContextStore<String, T> {

  private static final ClassValue<? super ContextStore<String, ?>> classInstanceStore =
      GenericClassValue.of(input -> new InstanceStore<>());

  /**
   * @return global store of instances with the same common type
   */
  @SuppressWarnings("unchecked")
  public static <T> InstanceStore<T> of(Class<T> type) {
    return (InstanceStore<T>) classInstanceStore.get(type);
  }

  // simple approach; instance stores don't need highly concurrent access or weak keys
  private final Map<String, T> store = Collections.synchronizedMap(new HashMap<>());

  private InstanceStore() {}

  @Override
  public T get(String key) {
    return store.get(key);
  }

  @Override
  public void put(String key, T instance) {
    store.put(key, instance);
  }

  @Override
  public T putIfAbsent(String key, T instance) {
    T existing = store.putIfAbsent(key, instance);
    return existing != null ? existing : instance;
  }

  @Override
  public T putIfAbsent(String key, Factory<T> instanceFactory) {
    return store.computeIfAbsent(key, instanceFactory::create);
  }

  @Override
  public T computeIfAbsent(String key, KeyAwareFactory<? super String, T> instanceFactory) {
    return store.computeIfAbsent(key, instanceFactory::create);
  }

  @Override
  public T remove(String key) {
    return store.remove(key);
  }
}
