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
 *
 * <p>A refused claim is a reservation too, rather than a {@code null}, and one that quietly
 * discards whatever is filled into it. Nothing about the failed path throws, so the shortest
 * correct call site is also the obvious one:
 *
 * <pre>{@code
 * try (Reservation<Task> place = queue.tryReserve()) {
 *   place.fill(buildTask());
 * }
 * }</pre>
 *
 * <p>Consulting {@link #granted} first is what buys the reserve-first guarantee — skip the build
 * and nothing is allocated for a queue that had no room for it:
 *
 * <pre>{@code
 * try (Reservation<Task> place = queue.tryReserve()) {
 *   if (place.granted()) {
 *     place.fill(buildTask());
 *   }
 * }
 * }</pre>
 */
public interface Reservation<T> extends AutoCloseable {

  /**
   * Whether a place was actually claimed. Worth asking before building anything expensive: a
   * refused reservation accepts a fill and throws it away, so checking is what turns
   * allocate-then-drop into never-allocate.
   *
   * @return whether a fill will be kept
   */
  boolean granted();

  /**
   * Publishes {@code element} into the claimed place. A granted place is already paid for, so this
   * cannot be rejected; a refused one discards the element, having already counted the drop.
   */
  void fill(T element);

  /**
   * Gives the place back if it was never filled, immediately. Filling first makes this a no-op, and
   * nothing is ever consumed for a released place.
   */
  @Override
  void close();
}
