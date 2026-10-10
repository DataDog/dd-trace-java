package datadog.common.queue;

import datadog.trace.api.function.Strategy;

/**
 * A {@link Producer} that derives its element from a caller-supplied context.
 *
 * <p>The context parameter is what lets the producer stay non-capturing: state the element needs is
 * passed in at the call site rather than closed over.
 */
@Strategy
@FunctionalInterface
public interface ContextualProducer<C, T> {
  T produce(C context);
}
