package datadog.trace.api.featureflag.flagevaluation;

/**
 * Defines an EVP flagevaluation writer responsible for aggregating flag evaluation events and
 * flushing them to the EVP proxy.
 *
 * <p>Implementations must use a background thread (serializing handler) for aggregation and
 * transport. The {@link #enqueue(FlagEvalEvent)} method must be non-blocking and callable from the
 * OpenFeature hook thread without backpressure.
 */
public interface FlagEvaluationWriter extends AutoCloseable {

  /**
   * Non-blocking enqueue of a flag evaluation event. May silently drop the event if the internal
   * bounded queue is full (best-effort, observable via drop counter).
   *
   * @param event the flag evaluation event captured at hook-fire time
   */
  void enqueue(FlagEvalEvent event);

  /** Starts the background serializing thread. Must be called once after construction. */
  void start();

  /** Stops the background thread and releases resources. */
  @Override
  void close();
}
