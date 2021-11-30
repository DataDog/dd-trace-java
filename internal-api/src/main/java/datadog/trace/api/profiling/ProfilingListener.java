package datadog.trace.api.profiling;

/**
 * A simple generified listener/observer type
 *
 * @param <Observable> the observed data type
 */
public interface ProfilingListener<Observable> {
  void onData(Observable observable);
}
