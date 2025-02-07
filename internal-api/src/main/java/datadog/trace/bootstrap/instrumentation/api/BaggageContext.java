package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import java.util.Map;

public class BaggageContext implements ImplicitContextKeyed {
  private static final ContextKey<BaggageContext> CONTEXT_KEY = ContextKey.named("baggage-key");

  private Map<String, String> baggage;

  public static BaggageContext create(Map<String, String> baggage) {
    return new BaggageContext(baggage);
  }

  private BaggageContext(Map<String, String> baggage) {
    this.baggage = baggage;
  }

  public static BaggageContext fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  public Map<String, String> getBaggage() {
    return baggage;
  }

  @Override
  public Context storeInto(Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
