package datadog.common.queue;

/**
 * A claimed place in a {@link WorkQueue}, for a caller whose work between claiming and filling
 * cannot be expressed as a {@link Producer}.
 *
 * <p>What is claimed is capacity, never a position: the element joins the queue where it is filled,
 * not where it was claimed, so an open reservation holds no place a consumer could be waiting on
 * and cannot stall one. A reservation that is neither filled nor closed does leak its capacity,
 * quietly and permanently, which is why this is an {@link AutoCloseable} meant for
 * try-with-resources.
 */
public interface Reservation<T> extends AutoCloseable {

  /**
   * Publishes {@code element} into the claimed place. The place is already claimed, so this cannot
   * fail and cannot be rejected.
   */
  void fill(T element);

  /**
   * Gives the place back if it was never filled, immediately. Filling first makes this a no-op, and
   * nothing is ever consumed for a released place.
   */
  @Override
  void close();
}
