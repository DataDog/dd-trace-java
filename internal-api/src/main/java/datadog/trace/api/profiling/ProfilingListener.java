package datadog.trace.api.profiling;

/**
 * A simple generified listener/observer type
 *
 * @param <Type> the observed data type
 */
public interface ProfilingListener<Type> {
  void onData(Type observable);
}
