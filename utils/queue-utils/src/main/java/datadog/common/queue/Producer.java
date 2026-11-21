package datadog.common.queue;

import datadog.trace.api.function.Strategy;

/**
 * Produces an element after a {@link WorkQueue} has claimed capacity for it.
 *
 * <p>Prefer a reusable producer or a non-capturing lambda. The contextual producer overloads carry
 * per-admission state without capturing it. Constructing a capturing lambda for each admission may
 * allocate; HotSpot can eliminate that allocation through escape analysis when the call chain
 * inlines. {@link Strategy} describes the intended optimisation pattern, without guaranteeing it.
 *
 * <p>Use {@link WorkQueue#tryReserve()} when the work between claiming and filling cannot fit a
 * producer callback. A producer returning null declines the element and releases its claimed place.
 *
 * @param <T> the produced item type
 */
@Strategy
@FunctionalInterface
public interface Producer<T> {
  T produce();
}
