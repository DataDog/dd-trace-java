package datadog.common.queue;

/**
 * Produces an element for admission into a {@link WorkQueue}.
 *
 * <p>A producer is only invoked once a slot has been reserved, so it is never called for an element
 * that will be rejected. Implementations are expected to be non-capturing {@code static final}
 * singletons; a capturing lambda allocates per call and defeats the purpose of deferring
 * construction.
 */
@FunctionalInterface
public interface Producer<T> {
  T produce();
}
