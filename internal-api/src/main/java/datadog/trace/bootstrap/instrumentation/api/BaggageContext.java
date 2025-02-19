package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import java.util.Map;

public class BaggageContext implements ImplicitContextKeyed {
  private static final ContextKey<BaggageContext> CONTEXT_KEY = ContextKey.named("baggage-key");

  private final Map<String, String> baggage;
  private String baggageString;
  private boolean updatedCache;

  public static BaggageContext create(Map<String, String> baggage, String baggageString) {
    return new BaggageContext(baggage, baggageString);
  }

  private BaggageContext(Map<String, String> baggage, String baggageString) {
    this.baggage = baggage;
    this.baggageString = baggageString;
    updatedCache = true;
  }

  public void addBaggage(String key, String value) {
    baggage.put(key, value);
    updatedCache = false;
  }

  public void removeBaggage(String key) {
    baggage.remove(key);
    updatedCache = false;
  }

  public void setBaggageString(String baggageString) {
    this.baggageString = baggageString;
    updatedCache = true;
  }

  public boolean isUpdatedCache() {
    return updatedCache;
  }

  public String getBaggageString() {
    return baggageString;
  }

  public Map<String, String> getBaggage() {
    return baggage;
  }

  public static BaggageContext fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  @Override
  public Context storeInto(Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
