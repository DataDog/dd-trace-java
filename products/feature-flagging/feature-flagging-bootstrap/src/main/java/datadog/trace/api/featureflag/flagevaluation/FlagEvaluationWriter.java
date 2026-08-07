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

  /**
   * Reports whether the internal queue currently has room for another event. Producers should
   * consult this <em>before</em> performing expensive context-copy work so a saturated queue is
   * observed as an O(1) read rather than a full snapshot followed by a discarded offer.
   *
   * <p>Best-effort: the worker can drain (or a peer producer can fill) between this check and the
   * next {@link #enqueue}, so callers must still tolerate offer failure.
   */
  boolean hasCapacityForEnqueue();

  /** Counts one queue-overflow drop without offering an event. */
  void countPreQueueOverflow();

  /**
   * Counts one evaluation whose context was truncated by {@code copyPrunedContext}. The {@code
   * reason} string is the sorted, comma-separated set of cap names that fired (e.g. {@code
   * "max_key_length,max_value_length"}). Each unique reason string is counted separately so
   * telemetry can distinguish which caps are hot.
   *
   * @param reason non-null, non-empty sorted comma-separated reason tag value
   */
  void countContextTruncated(String reason);

  /** Starts the background serializing thread. Must be called once after construction. */
  void start();

  /** Stops the background thread and releases resources. */
  @Override
  void close();
}
