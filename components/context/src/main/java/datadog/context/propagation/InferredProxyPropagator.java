package datadog.context.propagation;

import datadog.context.Context;
import java.util.function.BiConsumer;

public class InferredProxyPropagator implements Propagator {
  static final String INFERRED_PROXY_KEY = "x-dd-proxy";
  /**
   * Injects a context into a downstream service using the given carrier.
   *
   * @param context the context containing the values to be injected.
   * @param carrier the instance that will receive the key/value pairs to propagate.
   * @param setter the callback to set key/value pairs into the carrier.
   */
  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    // TODO: find out does any inferred proxy info need to be injected to downstream services??
    // afaik this shouldnt be used
    if (carrier == null) {
      return;
    }
    setter.set(carrier, INFERRED_PROXY_KEY, context.toString());
  }

  /**
   * Extracts a context from un upstream service.
   *
   * @param context the base context to store the extracted values on top, use {@link
   *     Context#root()} for a default base context.
   * @param carrier the instance to fetch the propagated key/value pairs from.
   * @param visitor the callback to walk over the carrier and extract its key/value pais.
   * @return A context with the extracted values on top of the given base context.
   */
  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    if (context == null || carrier == null || visitor == null) {
      return context;
    }
    InferredProxyContextExtractor extractor = new InferredProxyContextExtractor();
    visitor.forEachKeyValue(carrier, extractor);

    // Extracted extracted =
    return null;
  }

  // TODO implement HTTP header parser rules
  public static class InferredProxyContextExtractor implements BiConsumer<String, String> {
    // private InferredProxyContext extracted context

    /**
     * Performs this operation on the given arguments.
     *
     * @param s the first input argument
     * @param s2 the second input argument
     */
    @Override
    public void accept(String s, String s2) {}
  }
}
