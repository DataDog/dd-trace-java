package datadog.common.queue;

/**
 * A claimed place in a {@link WorkQueue}, for the rare caller that must do work between claiming
 * and filling and so cannot express its admission as a {@link Producer}.
 *
 * <p>The place is claimed where the reservation was taken, and a consumer will not see past it
 * until it is filled or released — so an open reservation stalls the consumer, and one that is
 * never closed stalls it forever. Take one only in try-with-resources, hold it for as long as it
 * takes to build one element, and prefer the producer forms of {@code tryPut}, which cannot be
 * leaked.
 */
public interface Reservation<T> extends AutoCloseable {

  /**
   * Publishes {@code element} into the claimed place. The place is already claimed, so this cannot
   * fail and cannot be rejected.
   */
  void fill(T element);

  /** Releases the place if it was never filled. Filling first makes this a no-op. */
  @Override
  void close();
}
