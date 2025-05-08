package datadog.context.propagation;

import datadog.context.Context;
import datadog.context.InferredProxyContext;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class InferredProxyPropagator implements Propagator {
  public static final String INFERRED_PROXY_KEY = "x-dd-proxy";
  /**
   * METHOD STUB: InferredProxy is currently not meant to be injected to downstream services Injects
   * a context into a downstream service using the given carrier.
   *
   * @param context the context containing the values to be injected.
   * @param carrier the instance that will receive the key/value pairs to propagate.
   * @param setter the callback to set key/value pairs into the carrier.
   */
  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {}

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

    InferredProxyContext extractedContext = extractor.extractedContext;
    if (extractedContext == null) {
      return context;
    }
    return extractedContext.storeInto(context);
  }

  public static class InferredProxyContextExtractor implements BiConsumer<String, String> {
    private InferredProxyContext extractedContext;

    InferredProxyContextExtractor() {}

    private Map<String, String> parseInferredProxyHeaders(String input) {
      Map<String, String> parsedHeaders = new HashMap<>();
      return parsedHeaders;
    }

    /**
     * Performs this operation on the given arguments.
     *
     * @param key the first input argument from an http header
     * @param value the second input argument from an http header
     */
    @Override
    public void accept(String key, String value) {
      if (key == null || key.isEmpty() || !key.startsWith(INFERRED_PROXY_KEY)) {
        return;
      }
      Map<String, String> inferredProxyMap = parseInferredProxyHeaders(value);
      if (extractedContext == null) {
        extractedContext = new InferredProxyContext();
      }
      extractedContext.putInferredProxyInfo(key, value);
    }
  }
}
