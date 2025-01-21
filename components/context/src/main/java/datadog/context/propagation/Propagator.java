package datadog.context.propagation;

import datadog.context.Context;

/**
 * This interface represents a {@link Context} propagator for a given {@link Concern}.
 *
 * <p>Its goal is to {@link #inject} context values into carriers, or {@link #extract} them from
 * carriers to populate context. Carrier could be any kind of object that stores key/value pairs,
 * like HTTP or messages headers. {@link CarrierSetter}s and {@link CarrierVisitor}s define how to
 * store and retrieve key/value pairs from carriers.
 */
public interface Propagator {
  /**
   * Injects a context into a downstream service using the given carrier.
   *
   * @param context the context containing the values to be injected.
   * @param carrier the instance that will receive the key/value pairs to propagate.
   * @param setter the callback to set key/value pairs into the carrier.
   * @param <C> the type of carrier instance.
   */
  <C> void inject(Context context, C carrier, CarrierSetter<C> setter);

  /**
   * Extracts a context from un upstream service.
   *
   * @param context the base context to store the extracted values on top, use {@link
   *     Context#root()} for a default base context.
   * @param carrier the instance to fetch the propagated key/value pairs from.
   * @param visitor the callback to walk over the carrier and extract its key/value pais.
   * @param <C> the type of the carrier.
   * @return A context with the extracted values on top of the given base context.
   */
  <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor);
}
