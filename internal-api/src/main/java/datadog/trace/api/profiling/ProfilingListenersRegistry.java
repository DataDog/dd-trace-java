package datadog.trace.api.profiling;

/**
 * A {@linkplain ProfilingListeners} registry. Allows retrieving a specific {@linkplain
 * ProfilingListeners} instance for the given observable type.
 */
public final class ProfilingListenersRegistry {
  private static final ClassValue<ProfilingListeners<ObservableType>> listenersPerType =
      new ClassValue<ProfilingListeners<ObservableType>>() {
        @Override
        protected ProfilingListeners<ObservableType> computeValue(Class<?> type) {
          return new ProfilingListeners<>();
        }
      };

  private ProfilingListenersRegistry() {}

  /**
   * Retrieve the {@Profiling}
   *
   * @param type
   * @param <T>
   * @return
   */
  public static <T extends ObservableType> ProfilingListeners<T> getHost(Class<T> type) {
    return (ProfilingListeners<T>) listenersPerType.get(type);
  }
}
