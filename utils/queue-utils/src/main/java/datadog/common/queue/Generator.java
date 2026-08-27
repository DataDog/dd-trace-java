package datadog.common.queue;

/**
 * A sequence of elements pulled into a {@link WorkQueue} one at a time, in the manner of a Python
 * generator: asked for the next element until it says there is none.
 *
 * <p>Where a {@link Producer} yields the one element a caller has already decided to admit, a
 * generator is asked repeatedly and decides how many there are as it goes. The queue claims a place
 * before each call, so a generator is only ever asked for an element there is already room for:
 * nothing is built and then dropped, and nothing is yielded and then lost. That is also why a
 * caller batching work through a generator never holds capacity of its own — there is nothing to
 * over-claim, leak, or starve other producers with.
 *
 * <p>Generators are stateful by nature, holding their own position, so unlike a {@link Producer}
 * one cannot usefully be a shared non-capturing constant. Either allocate one per batch or keep a
 * reusable one and reset it.
 */
public interface Generator<T> {

  /**
   * @return the next element, or {@code null} when the sequence is finished
   */
  T next();
}
