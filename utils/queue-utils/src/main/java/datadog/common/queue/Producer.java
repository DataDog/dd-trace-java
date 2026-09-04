package datadog.common.queue;

import datadog.trace.api.function.Strategy;

/**
 * Produces an element for admission into a {@link WorkQueue}.
 *
 * <p>A producer is only invoked once a place has been claimed, so it is never called for an element
 * that will be rejected. Implementations must be non-capturing — a {@code static final} constant of
 * the concrete type, or a lambda that closes over nothing — which is what {@link Strategy} marks.
 *
 * <p>That is not a preference, it is the whole reason this form exists. A capturing lambda
 * allocates once per call, and so does a {@link Reservation}; the reservation is straight-line code
 * that keeps whatever the call site had hoisted and needs no context parameters. So a producer that
 * captures is strictly worse than the reserve form it was meant to improve on. If the state will
 * not fit the context parameters, use {@link WorkQueue#tryReserve} rather than closing over it.
 */
@Strategy
@FunctionalInterface
public interface Producer<T> {
  T produce();
}
