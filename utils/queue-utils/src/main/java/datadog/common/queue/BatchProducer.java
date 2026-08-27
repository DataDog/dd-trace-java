package datadog.common.queue;

/**
 * Supplies a sequence of elements that a {@link WorkQueue} pulls incrementally as capacity allows.
 *
 * <p>Used by {@link WorkQueue#put(BatchProducer)} for lossless admission: the queue drives the
 * iteration, so elements are constructed only as slots become available rather than materialised up
 * front.
 */
public interface BatchProducer<T> {
  boolean hasNext();

  T next();
}
