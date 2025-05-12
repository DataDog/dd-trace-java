package datadog.trace.api.gateway.inferredproxy;

import static datadog.context.ContextKey.named;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;

public class InferredProxyHeaders implements ImplicitContextKeyed {
  private static final ContextKey<InferredProxyHeaders> CONTEXT_KEY = named("inferred-proxy-key");
  private final Map<String, String> values;

  public static InferredProxyHeaders fromValues(Map<String, String> values) {
    return new InferredProxyHeaders(values);
  }

  public static InferredProxyHeaders fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  private InferredProxyHeaders(Map<String, String> values) {
    this.values = values == null ? Collections.emptyMap() : values;
  }

  public @Nullable String getValue(String key) {
    return this.values.get(key);
  }

  public int size() {
    return this.values.size();
  }

  @Override
  public Context storeInto(Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
