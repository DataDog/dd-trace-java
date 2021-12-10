package datadog.trace.api.profiling;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A listener host. Allows registering listeners and firing 'on-data' events
 *
 * @param <Type> the observable type
 */
public final class ProfilingListeners<Type extends ObservableType> {
  private final Collection<ProfilingListener<Type>> listeners = new ConcurrentLinkedQueue<>();

  ProfilingListeners() {}

  /**
   * Notify all listeners about the data
   *
   * @param data the observed data
   */
  public void fireOnData(Type data) {
    for (ProfilingListener<Type> listener : listeners) {
      listener.onData(data);
    }
  }

  /**
   * Add a listener
   *
   * @param listener listener instance
   */
  public void addListener(ProfilingListener<Type> listener) {
    listeners.add(listener);
  }

  /**
   * Remove a listener
   *
   * @param listener listener instance
   */
  public void removeListener(ProfilingListener<Type> listener) {
    listeners.remove(listener);
  }
}
