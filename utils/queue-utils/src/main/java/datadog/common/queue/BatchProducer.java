package datadog.common.queue;

/**
 * Supplies a sequence of elements that a {@link Queue} pulls incrementally as capacity allows.
 *
 * <p>Used by {@link Queue#put(BatchProducer)} for lossless admission: the queue drives the
 * iteration, so elements are constructed only as slots become available rather than materialised up
 * front.
 */
public interface BatchProducer<T> {
  boolean hasNext();

  T next();
}
