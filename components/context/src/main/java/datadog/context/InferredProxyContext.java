package datadog.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class InferredProxyContext implements ImplicitContextKeyed {
  public static final ContextKey<InferredProxyContext> CONTEXT_KEY =
      ContextKey.named("inferred-proxy-key");
  private final Map<String, String> inferredProxy;

  public static InferredProxyContext fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  public InferredProxyContext(Map<String, String> contextInfo) {
    this.inferredProxy =
        (contextInfo == null || contextInfo.isEmpty())
            ? new HashMap<>()
            : new HashMap<>(contextInfo);
  }

  public InferredProxyContext() {
    this.inferredProxy = new HashMap<>();
  }

  public Map<String, String> getInferredProxyContext() {
    return Collections.unmodifiableMap(inferredProxy);
  }

  public void putInferredProxyInfo(String key, String value) {
    inferredProxy.put(key, value);
  }

  public void removeInferredProxyInfo(String key) {
    inferredProxy.remove(key);
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
