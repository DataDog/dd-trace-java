package datadog.common.queue;

/**
 * A claimed place in a {@link WorkQueue}, for the rare caller that must do work between claiming
 * and filling and so cannot express its admission as a {@link Producer}.
 *
 * <p>Capacity is claimed when the reservation is taken and held until it is filled or released, so
 * one that is never closed leaks capacity, and on an array-backed queue — where the claim is a slot
 * the consumer cannot see past — stalls the consumer as well. Take one only in try-with-resources,
 * hold it for as long as it takes to build one element, and prefer the producer forms of {@code
 * tryPut}, which cannot be leaked.
 */
public interface Reservation<T> extends AutoCloseable {

  /**
   * Publishes {@code element} into the claimed place. The place is already claimed, so this cannot
   * fail and cannot be rejected.
   */
  void fill(T element);

  /**
   * Releases the place if it was never filled. Filling first makes this a no-op.
   *
   * <p>Nothing is ever consumed for a released place. Where the claim was a slot, the capacity
   * comes back as the consumer passes over it rather than the instant it is released.
   */
  @Override
  void close();
}
