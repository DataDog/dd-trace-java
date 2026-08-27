package datadog.common.queue;

import datadog.trace.api.function.Strategy;

/**
 * A {@link Producer} that derives its element from two caller-supplied contexts.
 *
 * <p>Two rather than one because the second context is typically a value the call site hoisted out
 * of a loop — a schema, a clock reading, a per-batch buffer — that the item alone cannot recover.
 * Carrying it as a parameter is what keeps the producer a non-capturing bound-once field and keeps
 * the hoist visible where it happens, instead of a per-iteration capture or a cached binding that
 * can silently go stale.
 *
 * <p>The ladder stops here on purpose. A third context is usually derivable from the item, and a
 * primitive one has to be boxed to ride a generic parameter, which costs more than re-deriving it.
 * A call site that genuinely needs more should close over what it needs once per scope.
 */
@Strategy
@FunctionalInterface
public interface BiContextualProducer<C1, C2, T> {
  T produce(C1 first, C2 second);
}
