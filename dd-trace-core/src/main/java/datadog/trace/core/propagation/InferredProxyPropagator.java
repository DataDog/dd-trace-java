package datadog.trace.core.propagation;

import static datadog.trace.api.gateway.InferredProxySpan.fromHeaders;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.gateway.InferredProxySpan;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;

/** Inferred proxy propagator. Only extract, not meant for injection. */
@ParametersAreNonnullByDefault
public class InferredProxyPropagator implements Propagator {
  private static final String INFERRED_PROXY_KEY_PREFIX = "x-dd-proxy";

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {}

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    if (context == null || carrier == null || visitor == null) {
      return context;
    }
    InferredProxyContextExtractor extractor = new InferredProxyContextExtractor();
    visitor.forEachKeyValue(carrier, extractor);
    InferredProxySpan inferredProxySpan = extractor.inferredProxySpan();
    if (inferredProxySpan != null) {
      context = context.with(inferredProxySpan);
    }
    return context;
  }

  /** Extract inferred proxy related headers into a map. */
  private static class InferredProxyContextExtractor implements BiConsumer<String, String> {
    private Map<String, String> values;

    @Override
    public void accept(String key, String value) {
      if (key == null || key.isEmpty() || !key.startsWith(INFERRED_PROXY_KEY_PREFIX)) {
        return;
      }
      if (values == null) {
        this.values = new HashMap<>();
      }
      this.values.put(key, value);
    }

    public InferredProxySpan inferredProxySpan() {
      if (this.values == null) {
        return null;
      }
      InferredProxySpan inferredProxySpan = fromHeaders(this.values);
      return inferredProxySpan.isValid() ? inferredProxySpan : null;
    }
  }
}
