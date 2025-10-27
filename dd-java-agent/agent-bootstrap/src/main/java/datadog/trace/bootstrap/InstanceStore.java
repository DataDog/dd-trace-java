package datadog.trace.bootstrap;

import datadog.trace.api.GenericClassValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * An {@code InstanceStore} is a class global map for registering instances. This can be useful when
 * helper classes are injected into multiple class loaders and need to share instances of a type
 * from a common parent class loader.
 *
 * <p>Instance keys are expected to be string literals, defined as constants in the helper classes.
 */
public final class InstanceStore<T> {

  @SuppressWarnings("rawtypes")
  private static final ClassValue<InstanceStore> classInstanceStore =
      GenericClassValue.of(type -> new InstanceStore<>());

  /** @return global store of instances with the same common type */
  @SuppressWarnings("unchecked")
  public static <T> InstanceStore<T> of(Class<T> type) {
    return classInstanceStore.get(type);
  }

  // simple approach; instance stores don't need highly concurrent access or weak keys
  private final Map<String, T> store = Collections.synchronizedMap(new HashMap<>());

  private InstanceStore() {}

  /**
   * Gets the instance of {@code T} currently associated with the given key.
   *
   * @param key the instance key
   * @return the associated instance
   */
  public T get(String key) {
    return store.get(key);
  }

  /**
   * Unconditionally associates an instance of {@code T} with the given key.
   *
   * @param key the instance key
   * @param instance the instance
   */
  public void put(String key, T instance) {
    store.put(key, instance);
  }

  /**
   * If the given key is not already associated with an instance, create one using the factory and
   * associate it. Unlike {@link java.util.Map#putIfAbsent} this always returns the final associated
   * instance.
   *
   * @param key the instance key
   * @param instanceFactory the factory to create instances
   * @return final associated instance
   */
  public T putIfAbsent(String key, Supplier<T> instanceFactory) {
    return store.computeIfAbsent(key, k -> instanceFactory.get());
  }

  /**
   * Removes the instance of {@code T} currently associated with the given key.
   *
   * @param key the instance key
   * @return the previously associated instance; {@code null} if there was none
   */
  public T remove(String key) {
    return store.remove(key);
  }
}
