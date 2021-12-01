package datadog.trace.api.profiling;

/**
 * A {@linkplain ProfilingListenerHost} registry. Allows retrieving a specific {@linkplain
 * ProfilingListenerHost} instance for the given observable type.
 */
public final class ProfilingListenerHosts {
  private static final ClassValue<ProfilingListenerHost<ObservableType>> listenersPerType =
      new ClassValue<ProfilingListenerHost<ObservableType>>() {
        @Override
        protected ProfilingListenerHost<ObservableType> computeValue(Class<?> type) {
          return new ProfilingListenerHost<>();
        }
      };

  private ProfilingListenerHosts() {}

  /**
   * Retrieve the {@Profiling}
   *
   * @param type
   * @param <T>
   * @return
   */
  public static <T extends ObservableType> ProfilingListenerHost<T> getHost(Class<T> type) {
    return (ProfilingListenerHost<T>) listenersPerType.get(type);
  }
}
