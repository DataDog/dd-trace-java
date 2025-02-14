package datadog.context;

import java.util.Map;

public class InferredProxyContext implements ImplicitContextKeyed {
  private static final ContextKey<InferredProxyContext> CONTEXT_KEY =
      ContextKey.named("inferred-proxy-key");
  private Map<String, String> inferredProxy;

  public Map<String, String> getInferredProxyContext() {
    return inferredProxy;
  }

  public InferredProxyContext(Map<String, String> contextInfo) {
    this.inferredProxy = contextInfo;
  }

  /**
   * Creates a new context with this value under its chosen key.
   *
   * @param context the context to copy the original values from.
   * @return the new context with the implicitly keyed value.
   * @see Context#with(ImplicitContextKeyed)
   */
  @Override
  public Context storeInto(Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
