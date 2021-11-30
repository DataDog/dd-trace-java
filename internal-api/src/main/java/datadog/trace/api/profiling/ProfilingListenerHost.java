package datadog.trace.api.profiling;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A listener host. Allows registering listeners and firing 'on-data' events
 *
 * @param <T> the observable type
 */
public final class ProfilingListenerHost<T extends ObservableType> {
  private final Collection<ProfilingListener<T>> listeners = new ConcurrentLinkedQueue<>();

  ProfilingListenerHost() {}

  /**
   * Notify all listeners about the data
   *
   * @param data the observed data
   */
  public void fireOnData(T data) {
    for (ProfilingListener<T> listener : listeners) {
      listener.onData(data);
    }
  }

  /**
   * Add a listener
   *
   * @param listener listener instance
   */
  public void addListener(ProfilingListener<T> listener) {
    listeners.add(listener);
  }

  /**
   * Remove a listener
   *
   * @param listener listener instance
   */
  public void removeListener(ProfilingListener<T> listener) {
    listeners.remove(listener);
  }
}
