package datadog.common.queue;

/**
 * The placeholder a {@link Reservation} leaves in the backing store, so the claimed place keeps its
 * position in the queue while the caller builds the element that goes in it.
 *
 * <p>The consumer distinguishes a slot from an ordinary element by type, which is why every backing
 * stores {@code Object} rather than {@code T}.
 */
final class Slot<T> implements Reservation<T> {

  /** Distinguishes "released without ever being filled" from "still open". */
  static final Object RELEASED = new Object();

  /** Written by the reserving thread, read by the consumer; null while the place is still open. */
  private volatile Object element;

  Object element() {
    return element;
  }

  @Override
  public void fill(T element) {
    if (element == null) {
      throw new NullPointerException("a queue cannot hold null");
    }
    this.element = element;
  }

  @Override
  public void close() {
    // Only the reserving thread calls fill and close, so a plain check orders them correctly.
    if (element == null) {
      element = RELEASED;
    }
  }
}
